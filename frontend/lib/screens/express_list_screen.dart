// "我的快递"页（DAY5 本地化版）。
//
// 打开应用 → 申请短信权限 → 读收件箱（按读取周期时间戳下沉过滤）→ 解析入库（去重）→ 展示卡片。
// 数据全本地，不依赖后端。受 AppConfig.enableLocalSms 开关控制。
// 读取周期由 settingsStore 控制，变化时自动重读。
// 妈妈版：从通知点进应用时自动重扫（onResume）；权限补齐 RECEIVE_SMS/通知
//（permission_handler 只管 READ_SMS，原生侧 channel 补弹剩余系统授权窗）。

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../app_config.dart';
import '../models/express_info.dart';
import '../parsers/sms_parser.dart';
import '../services/express_store.dart';
import '../services/settings_store.dart';
import '../services/sms_service.dart';
import '../widgets/express_card.dart';

class ExpressListScreen extends StatefulWidget {
  const ExpressListScreen({super.key});

  @override
  State<ExpressListScreen> createState() => _ExpressListScreenState();
}

class _ExpressListScreenState extends State<ExpressListScreen>
    with WidgetsBindingObserver {
  static const _native = MethodChannel('express/native');

  final _sms = SmsService.instance;
  final _store = ExpressStore.instance;
  List<ExpressInfo> _items = const [];
  bool _loading = true;
  String? _error;
  bool _permissionsRequested = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    settingsStore.addListener(_onSettingsChange);
    WidgetsBinding.instance.addPostFrameCallback((_) => _load());
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    settingsStore.removeListener(_onSettingsChange);
    super.dispose();
  }

  // 妈妈点通知打开应用 / 切回应用时自动重扫收件箱，保证列表最新
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && _permissionsRequested) {
      _load();
    }
  }

  // 读取周期变化时重读
  void _onSettingsChange() {
    if (mounted) _load();
  }

  int _loadSeq = 0; // 防抖序号，只接受最新一次 _load 的结果

  Future<void> _load() async {
    if (!AppConfig.enableLocalSms) {
      if (mounted) setState(() => _loading = false);
      return;
    }
    final seq = ++_loadSeq;
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      if (!mounted) return;
      // 权限触发：未授权先弹解释窗，不同意则退出应用（必要权限）
      final granted = await _sms.ensurePermission(context);
      if (seq != _loadSeq) return;
      if (!granted) {
        SystemNavigator.pop();
        return;
      }
      // 补齐 permission_handler 没覆盖的权限：RECEIVE_SMS（后台收短信）、
      // POST_NOTIFICATIONS（Android 13+ 弹通知）。只在首次进入时弹。
      if (!_permissionsRequested) {
        _permissionsRequested = true;
        try {
          await _native.invokeMethod('requestSmsPermissions');
        } catch (_) {
          // channel 失败不影响主流程，只是收不到新短信通知
        }
      }
      // 读取周期：近 N 天（默认14），算出时间戳下界
      final sinceMs = DateTime.now().millisecondsSinceEpoch -
          settingsStore.readDays * 24 * 60 * 60 * 1000;
      final messages = await _sms.readInbox(sinceMs: sinceMs);
      if (seq != _loadSeq) return;
      // 解析 + 入库去重（按短信 _id），原文一并入库兜底
      final items = messages
          .where((m) => m.id != null)
          .map((m) => (
                smsId: m.id!,
                smsDate: m.date,
                parsed: parseSms(m.body ?? ''),
                body: m.body ?? '',
              ))
          .toList();
      await _store.upsertAll(items);
      if (seq != _loadSeq) return;
      _items = await _store.queryAll(sinceMs);
    } catch (e) {
      if (seq != _loadSeq) return;
      _error = e.toString();
    } finally {
      if (seq == _loadSeq) {
        _loading = false;
        if (mounted) setState(() {});
      }
    }
  }

  Future<void> _toggle(ExpressInfo e) async {
    final newStatus = e.isPicked ? 0 : 1;
    try {
      await _store.updateStatus(e.id, newStatus);
      if (!mounted) return;
      // 本地切换状态（划线/取消划线），不立即重拉
      setState(() {
        _items = _items
            .map((x) => x.id == e.id ? x.copyWith(status: newStatus) : x)
            .toList();
      });
    } catch (err) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('操作失败：$err')));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('我的快递',
            style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold)),
        actions: [
          IconButton(
            onPressed: _loading ? null : _load,
            icon: const Icon(Icons.refresh, size: 28),
          ),
        ],
      ),
      body: _buildBody(),
    );
  }

  Widget _buildBody() {
    if (_loading) return const Center(child: CircularProgressIndicator());
    if (_error != null) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.cloud_off, size: 48, color: Colors.grey),
            const SizedBox(height: 8),
            Text('加载失败：$_error', textAlign: TextAlign.center),
            const SizedBox(height: 8),
            OutlinedButton(onPressed: _load, child: const Text('重试')),
          ],
        ),
      );
    }
    if (!AppConfig.enableLocalSms) {
      return const Center(
        child: Text('短信功能未启用', style: TextStyle(color: Colors.grey)),
      );
    }
    if (_items.isEmpty) {
      return const Center(
        child: Text(
          '还没有快递短信\n收到快递短信会自动出现在这里',
          textAlign: TextAlign.center,
          style: TextStyle(color: Colors.grey, fontSize: 17, height: 1.6),
        ),
      );
    }
    return ListView.builder(
      padding: const EdgeInsets.all(12),
      itemCount: _items.length,
      itemBuilder: (context, index) {
        final e = _items[index];
        return Padding(
          padding: const EdgeInsets.only(bottom: 10),
          child: ExpressCard(express: e, onStatusToggle: () => _toggle(e)),
        );
      },
    );
  }
}
