"""Android Root 定时构建专用的签名准备与 APK 产物校验。"""

import base64
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parent.parent
REQUIRED_LIBRARIES = (
    'libapp.so',
    'libflutter.so',
    'libclash.so',
    'libroot_tun_helper.so',
    'librust_api.so',
)


def read_version():
    """从应用唯一版本来源读取版本名和基础版本号。"""
    match = re.search(
        r'^version:\s*([0-9]+\.[0-9]+\.[0-9]+)\+([0-9]+)\s*$',
        (ROOT / 'pubspec.yaml').read_text(),
        re.MULTILINE,
    )
    if match is None:
        raise RuntimeError('无法读取 pubspec.yaml 中的应用版本')
    return match.group(1), int(match.group(2))


def escape_property(value):
    """按 Java Properties 的 InputStream 规则转义，保留密码中的空白与特殊字符。"""
    escaped = []
    utf16 = value.encode('utf-16-be')
    for index in range(0, len(utf16), 2):
        code = int.from_bytes(utf16[index:index + 2], 'big')
        character = chr(code)
        if character in '\\ =:#!':
            escaped.append('\\' + character)
        elif code < 0x20 or code > 0x7E:
            escaped.append(f'\\u{code:04x}')
        else:
            escaped.append(character)
    return ''.join(escaped)


def certificate_file():
    """将证书摘要放到运行器临时目录，避免随 APK 上传签名材料。"""
    return Path(os.environ['RUNNER_TEMP']) / 'root-release-cert.sha256'


def prepare_signing():
    """只接受完整的 Release 签名，缺失时直接失败，不进入 Debug 回退分支。"""
    required = ('KEYSTORE', 'KEY_ALIAS', 'STORE_PASSWORD', 'KEY_PASSWORD')
    missing = [key for key in required if not os.environ.get(key)]
    if missing:
        raise RuntimeError('缺少 Release 签名 Secrets：' + ', '.join(missing))

    os.umask(0o077)
    try:
        keystore = base64.b64decode(''.join(os.environ['KEYSTORE'].split()), validate=True)
    except ValueError:
        raise RuntimeError('KEYSTORE 必须是签名文件的有效 Base64') from None
    if not keystore:
        raise RuntimeError('Release 签名文件为空')
    store_file = ROOT / 'android/app/keystore.jks'
    store_file.write_bytes(keystore)
    store_file.chmod(0o600)

    # 导出证书而非私钥，后续逐包核对实际签名，密码仅通过环境变量传给 keytool。
    exported = subprocess.run(
        [
            'keytool', '-exportcert', '-keystore', str(store_file),
            '-alias', os.environ['KEY_ALIAS'], '-storepass:env', 'STORE_PASSWORD',
        ],
        capture_output=True,
    )
    if exported.returncode != 0 or not exported.stdout:
        raise RuntimeError('无法从 Release 签名文件读取证书，请检查签名 Secrets')
    certificate_file().write_text(hashlib.sha256(exported.stdout).hexdigest())

    version, version_code = read_version()
    properties = {
        'flutter.sdk': os.environ['FLUTTER_ROOT'],
        'sdk.dir': os.environ['ANDROID_HOME'],
        'flutter.buildMode': 'release',
        'flutter.versionName': version,
        'flutter.versionCode': str(version_code),
        'keyAlias': os.environ['KEY_ALIAS'],
        'storePassword': os.environ['STORE_PASSWORD'],
        'keyPassword': os.environ['KEY_PASSWORD'],
    }
    properties_file = ROOT / 'android/local.properties'
    properties_file.write_text(
        ''.join(f'{key}={escape_property(value)}\n' for key, value in properties.items()),
        encoding='ascii',
    )
    properties_file.chmod(0o600)
    with Path(os.environ['GITHUB_OUTPUT']).open('a') as output:
        output.write(f'version={version}\n')
    print('Release 签名准备完成')


def run_android_tool(tool, *arguments):
    """使用本次构建固定的 Android Build Tools，失败时保留工具诊断。"""
    executable = Path(os.environ['ANDROID_HOME']) / 'build-tools/37.0.0' / tool
    completed = subprocess.run([str(executable), *map(str, arguments)], capture_output=True, text=True)
    if completed.returncode != 0:
        raise RuntimeError(f'{tool} 校验失败：{completed.stdout}\n{completed.stderr}')
    return completed.stdout


def verify_apk(apk, abis, expected_version, expected_code, expected_certificate):
    """核对签名、正式包身份、版本和各 ABI 的完整原生库。"""
    signatures = run_android_tool('apksigner', 'verify', '--verbose', '--print-certs', apk)
    certificates = re.findall(r'^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]+)$', signatures, re.MULTILINE)
    if len(certificates) != 1 or certificates[0].lower() != expected_certificate:
        raise RuntimeError(f'{apk.name} 的签名与提供的 Release 证书不一致')
    run_android_tool('zipalign', '-c', '-P', '16', '4', apk)

    badging = run_android_tool('aapt', 'dump', 'badging', apk)
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.MULTILINE)
    if package is None or package.group(1) != 'com.follow.clash':
        raise RuntimeError(f'{apk.name} 不是正式版包名 com.follow.clash')
    if int(package.group(2)) != expected_code or package.group(3) != expected_version:
        raise RuntimeError(f'{apk.name} 的版本与 pubspec.yaml 不一致')
    if re.search(r'^application-debuggable(?:\s|$)', badging, re.MULTILINE):
        raise RuntimeError(f'{apk.name} 仍然开启 debuggable')
    declared = re.search(r'^native-code:(.*)$', badging, re.MULTILINE)
    if declared is None or set(re.findall(r"'([^']+)'", declared.group(1))) != abis:
        raise RuntimeError(f'{apk.name} 声明的 ABI 不完整或包含额外架构')

    with ZipFile(apk) as archive:
        entries = set(archive.namelist())
        packaged_abis = {name.split('/')[1] for name in entries if name.startswith('lib/') and name.endswith('.so')}
        if packaged_abis != abis:
            raise RuntimeError(f'{apk.name} 的原生库架构与声明不一致')
        for abi in sorted(abis):
            for library in REQUIRED_LIBRARIES:
                name = f'lib/{abi}/{library}'
                if name not in entries or archive.getinfo(name).file_size == 0:
                    raise RuntimeError(f'{apk.name} 缺少有效的 {name}')
    print(f'{apk.name}：签名、包名、版本、Release 属性和 ABI 校验通过')


def verify_outputs():
    """三个包全部验证成功后，才生成可供下载者核对的 SHA256SUMS。"""
    version, version_code = read_version()
    directory = ROOT / 'dist/root-release'
    expected_certificate = certificate_file().read_text().strip().lower()
    # ABI 分包版本号遵循 Flutter 的 +1000/+2000 规则，通用包使用基础版本号。
    packages = (
        ('armeabi-v7a', {'armeabi-v7a'}, version_code + 1000),
        ('arm64-v8a', {'arm64-v8a'}, version_code + 2000),
        ('universal', {'armeabi-v7a', 'arm64-v8a', 'x86_64'}, version_code),
    )
    expected_names = {f'FlClashForRoot-{version}-{architecture}-release.apk' for architecture, _, _ in packages}
    if {path.name for path in directory.glob('*.apk')} != expected_names:
        raise RuntimeError('Release 目录必须且只能包含 ARM32、ARM64 和 universal 三个 APK')
    checksums = []
    for architecture, abis, code in packages:
        apk = directory / f'FlClashForRoot-{version}-{architecture}-release.apk'
        verify_apk(apk, abis, version, code, expected_certificate)
        checksums.append(f'{hashlib.sha256(apk.read_bytes()).hexdigest()}  {apk.name}\n')
    (directory / 'SHA256SUMS').write_text(''.join(checksums), encoding='ascii')


def main():
    """明确区分签名准备和产物检查，避免构建阶段直接处理 Secrets。"""
    if sys.argv[1:] == ['prepare']:
        prepare_signing()
    elif sys.argv[1:] == ['verify']:
        verify_outputs()
    else:
        raise RuntimeError('用法：python3 tool/android_root_release.py prepare|verify')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, KeyError) as error:
        print(f'Android Root Release 失败：{error}', file=sys.stderr)
        sys.exit(1)
