import 'package:fl_clash/common/common.dart';
import 'package:fl_clash/providers/providers.dart';
import 'package:fl_clash/widgets/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

class PersonalizationView extends StatelessWidget {
  const PersonalizationView({super.key});

  /// 仅保存交互偏好，切换开关不会触发代理服务启停。
  @override
  Widget build(BuildContext context) {
    return CommonScaffold(
      title: context.appLocalizations.personalization,
      body: Builder(
        builder: (context) => ListView(
          padding: EdgeInsets.only(
            top: context.appBarInset,
            bottom: 20 + BottomInsetScope.of(context),
          ),
          children: [
            ConfigToggleItem(
              title: (l) => l.hideQuickStart,
              subtitle: (l) => l.hideQuickStartDesc,
              selector: appSettingProvider.select(
                (state) => state.hideQuickStart,
              ),
              onChanged: (ref, value) => ref
                  .read(appSettingProvider.notifier)
                  .update((state) => state.copyWith(hideQuickStart: value)),
            ),
          ],
        ),
      ),
    );
  }
}
