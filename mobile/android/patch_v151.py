from pathlib import Path

p = Path('mobile/android/app/src/main/java/com/alcomet/rollingcoilarchive/MobileActivity.java')
s = p.read_text(encoding='utf-8')

def rep(old, new, label):
    global s
    if old not in s:
        raise SystemExit(f'patch_v151: missing pattern: {label}')
    s = s.replace(old, new, 1)

rep('''    private final AtomicBoolean featureRefreshInFlight = new AtomicBoolean(false);\n    private volatile MobileFeatureClient.Policy mobilePolicy = MobileFeatureClient.Policy.disabled();''',
    '''    private final AtomicBoolean featureRefreshInFlight = new AtomicBoolean(false);\n    private final Handler policyHandler = new Handler(Looper.getMainLooper());\n    private final Runnable policyTick = new Runnable() { @Override public void run() {\n        if (destroyed) return;\n        refreshMobilePolicy();\n        policyHandler.postDelayed(this, 60000L);\n    }};\n    private volatile MobileFeatureClient.Policy mobilePolicy = MobileFeatureClient.Policy.disabled();''',
    'policy timer fields')

rep('''@Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); refreshMobilePolicy(); }''',
    '''@Override protected void onResume() {\n        super.onResume();\n        if (web != null) web.onResume();\n        refreshMobilePolicy();\n        policyHandler.removeCallbacks(policyTick);\n        policyHandler.postDelayed(policyTick,60000L);\n    }''',
    'resume policy timer')

rep('''@Override protected void onPause() { if (web != null) web.onPause(); super.onPause(); }''',
    '''@Override protected void onPause() {\n        policyHandler.removeCallbacks(policyTick);\n        if (web != null) web.onPause();\n        super.onPause();\n    }''',
    'pause policy timer')

rep('''        scanAction.setOnClickListener(v -> startBarcodeScan());\n        watchAction.setOnClickListener(v -> openWatchlistForCurrentPage());''',
    '''        scanAction.setOnClickListener(v -> verifyPolicyForAction(true));\n        watchAction.setOnClickListener(v -> verifyPolicyForAction(false));''',
    'revalidate feature before use')

old_refresh = '''    private void refreshMobilePolicy() {\n        if (!valid(config) || destroyed || !featureRefreshInFlight.compareAndSet(false,true)) return;\n        final MainActivity.Config snapshot = config;\n        executor.execute(() -> {\n            MobileFeatureClient.Policy p;\n            try { p = MobileFeatureClient.fetchPolicy(snapshot); }\n            catch (Exception e) { p = MobileFeatureClient.Policy.disabled(); }\n            final MobileFeatureClient.Policy ready = p;\n            runOnUiThread(() -> {\n                featureRefreshInFlight.set(false);\n                if (destroyed) return;\n                mobilePolicy = ready;\n                if (scanAction != null) scanAction.setVisibility(ready.qrBarcodeSearch ? View.VISIBLE : View.GONE);\n                if (watchAction != null) watchAction.setVisibility(ready.watchlist ? View.VISIBLE : View.GONE);\n                NotificationJobService.configure(this, ready);\n            });\n        });\n    }\n\n'''
new_refresh = '''    private void applyMobilePolicy(MobileFeatureClient.Policy ready) {\n        if (ready == null) ready = MobileFeatureClient.Policy.disabled();\n        mobilePolicy = ready;\n        if (scanAction != null) scanAction.setVisibility(ready.qrBarcodeSearch ? View.VISIBLE : View.GONE);\n        if (watchAction != null) watchAction.setVisibility(ready.watchlist ? View.VISIBLE : View.GONE);\n        NotificationJobService.configure(this, ready);\n    }\n\n    private void refreshMobilePolicy() {\n        if (!valid(config) || destroyed) {\n            applyMobilePolicy(MobileFeatureClient.Policy.disabled());\n            return;\n        }\n        if (!featureRefreshInFlight.compareAndSet(false,true)) return;\n        // Fail closed while policy is being refreshed. Disabled controls are never left visible\n        // because a stale cached policy happens to say ON.\n        applyMobilePolicy(MobileFeatureClient.Policy.disabled());\n        final MainActivity.Config snapshot = config;\n        executor.execute(() -> {\n            MobileFeatureClient.Policy p;\n            try { p = MobileFeatureClient.fetchPolicy(snapshot); }\n            catch (Exception e) { p = MobileFeatureClient.Policy.disabled(); }\n            final MobileFeatureClient.Policy ready = p;\n            runOnUiThread(() -> {\n                featureRefreshInFlight.set(false);\n                if (destroyed) return;\n                applyMobilePolicy(ready);\n            });\n        });\n    }\n\n    private void verifyPolicyForAction(boolean scanner) {\n        if (!valid(config) || destroyed) {\n            applyMobilePolicy(MobileFeatureClient.Policy.disabled());\n            toast("Mobile feature policy is unavailable.");\n            return;\n        }\n        // Re-check Engineering policy at the moment of use. This prevents a stale visible\n        // control from being usable after Engineering has switched the feature OFF.\n        if (scanner && scanAction != null) scanAction.setVisibility(View.GONE);\n        if (!scanner && watchAction != null) watchAction.setVisibility(View.GONE);\n        final MainActivity.Config snapshot = config;\n        executor.execute(() -> {\n            MobileFeatureClient.Policy p;\n            try { p = MobileFeatureClient.fetchPolicy(snapshot); }\n            catch (Exception e) { p = MobileFeatureClient.Policy.disabled(); }\n            final MobileFeatureClient.Policy ready = p;\n            runOnUiThread(() -> {\n                if (destroyed) return;\n                applyMobilePolicy(ready);\n                if (scanner) {\n                    if (!ready.qrBarcodeSearch) { toast("Scanner is disabled by Engineering."); return; }\n                    startBarcodeScan();\n                } else {\n                    if (!ready.watchlist) { toast("Watchlist is disabled by Engineering."); return; }\n                    openWatchlistForCurrentPage();\n                }\n            });\n        });\n    }\n\n'''
rep(old_refresh, new_refresh, 'fail closed policy refresh')

rep('''        destroyed = true; unregisterNetworkCallback(); connectInFlight.set(false); executor.shutdownNow();''',
    '''        destroyed = true;\n        policyHandler.removeCallbacks(policyTick);\n        unregisterNetworkCallback(); connectInFlight.set(false); executor.shutdownNow();''',
    'destroy policy timer')

p.write_text(s, encoding='utf-8')
print('patch_v151: strict live Engineering feature gates applied')
