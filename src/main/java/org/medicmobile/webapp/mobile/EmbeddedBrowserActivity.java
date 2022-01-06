package org.medicmobile.webapp.mobile;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static org.medicmobile.webapp.mobile.BuildConfig.DEBUG;
import static org.medicmobile.webapp.mobile.MedicLog.error;
import static org.medicmobile.webapp.mobile.MedicLog.log;
import static org.medicmobile.webapp.mobile.MedicLog.trace;
import static org.medicmobile.webapp.mobile.MedicLog.warn;
import static org.medicmobile.webapp.mobile.SimpleJsonClient2.redactUrl;
import static org.medicmobile.webapp.mobile.Utils.createUseragentFrom;
import static org.medicmobile.webapp.mobile.Utils.isValidNavigationUrl;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.webkit.ConsoleMessage;
import android.webkit.GeolocationPermissions;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.Arrays;

@SuppressWarnings({ "PMD.GodClass", "PMD.TooManyMethods" })
public class EmbeddedBrowserActivity extends LockableActivity {

	private WebView container;
	private SettingsStore settings;
	private String appUrl;
	private MrdtSupport mrdt;
	private PhotoGrabber photoGrabber;
	private SmsSender smsSender;
	private ChtExternalAppHandler chtExternalAppHandler;
	private boolean isMigrationRunning = false;

	static final String[] LOCATION_PERMISSIONS = { ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION };

	private static final ValueCallback<String> IGNORE_RESULT = new ValueCallback<String>() {
		public void onReceiveValue(String result) { /* ignore */ }
	};
	private final ValueCallback<String> backButtonHandler = new ValueCallback<String>() {
		public void onReceiveValue(String result) {
			if(!"true".equals(result)) {
				EmbeddedBrowserActivity.this.moveTaskToBack(false);
			}
		}
	};


//> ACTIVITY LIFECYCLE METHODS
	@Override public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		trace(this, "Starting webview...");

		this.photoGrabber = new PhotoGrabber(this);
		this.mrdt = new MrdtSupport(this);
		this.chtExternalAppHandler = new ChtExternalAppHandler(this);
		try {
			this.smsSender = new SmsSender(this);
		} catch(Exception ex) {
			error(ex, "Failed to create SmsSender.");
		}

		this.settings = SettingsStore.in(this);
		this.appUrl = settings.getAppUrl();

		this.requestWindowFeature(Window.FEATURE_NO_TITLE);
		setContentView(R.layout.main);

		// Add an alarming red border if using configurable (i.e. dev)
		// app with a medic production server.
		if (settings.allowsConfiguration() && appUrl != null && appUrl.contains("app.medicmobile.org")) {
			View webviewContainer = findViewById(R.id.lytWebView);
			webviewContainer.setPadding(10, 10, 10, 10);
			webviewContainer.setBackgroundColor(R.drawable.warning_background);
		}

		container = findViewById(R.id.wbvMain);

		configureUserAgent();

		setUpUiClient(container);
		enableRemoteChromeDebugging();
		enableJavascript(container);
		enableStorage(container);

		enableUrlHandlers(container);

		Intent appLinkIntent = getIntent();
		Uri appLinkData = appLinkIntent.getData();
		browseTo(appLinkData);

		if (settings.allowsConfiguration()) {
			toast(redactUrl(appUrl));
		}

		registerRetryConnectionBroadcastReceiver();

		String recentNavigation = settings.getLastUrl();
		if (isValidNavigationUrl(appUrl, recentNavigation)) {
			container.loadUrl(recentNavigation);
		}
	}

	@Override
	protected void onStart() {
		trace(this, "onStart() :: Checking Crosswalk migration ...");
		XWalkMigration xWalkMigration = new XWalkMigration(this.getApplicationContext());
		if (xWalkMigration.hasToMigrate()) {
			log(this, "onStart() :: Running Crosswalk migration ...");
			isMigrationRunning = true;
			Intent intent = new Intent(this, UpgradingActivity.class)
				.putExtra("isClosable", false)
				.putExtra("backPressedMessage", getString(R.string.waitMigration));
			startActivity(intent);
			xWalkMigration.run();
		} else {
			trace(this, "onStart() :: Crosswalk installation not found - skipping migration");
		}
		trace(this, "onStart() :: Checking Crosswalk migration done.");
		super.onStart();
	}

	@Override
	protected void onStop() {
		super.onStop();
		String recentNavigation = container.getUrl();
		if (isValidNavigationUrl(appUrl, recentNavigation)) {
			try {
				settings.setLastUrl(recentNavigation);
			} catch (SettingsException e) {
				error(e, "Error recording last URL loaded");
			}
		}
	}

	@Override public boolean onCreateOptionsMenu(Menu menu) {
		if(settings.allowsConfiguration()) {
			getMenuInflater().inflate(R.menu.unbranded_web_menu, menu);
		} else {
			getMenuInflater().inflate(R.menu.web_menu, menu);
		}
		return super.onCreateOptionsMenu(menu);
	}

	@Override public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == R.id.mnuGotoTestPages) {
			evaluateJavascript("window.location.href = 'https://medic.github.io/atp'");
			return true;
		}
		if (item.getItemId() == R.id.mnuSetUnlockCode) {
			changeCode();
			return true;
		}
		if (item.getItemId() == R.id.mnuSettings) {
			openSettings();
			return true;
		}
		if (item.getItemId() == R.id.mnuHardRefresh) {
			browseTo(null);
			return true;
		}
		if (item.getItemId() == R.id.mnuLogout) {
			evaluateJavascript("angular.element(document.body).injector().get('AndroidApi').v1.logout()");
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override public void onBackPressed() {
		trace(this, "onBackPressed()");
		container.evaluateJavascript(
				"angular.element(document.body).injector().get('AndroidApi').v1.back()",
				backButtonHandler);
	}

	@Override protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
		String requestCodeLabel = RequestCode.requestCodeToString(requestCode);

		try {
			trace(this, "onActivityResult() :: requestCode=%s, resultCode=%s", requestCodeLabel, resultCode);

			switch (requestCode) {
				case RequestCode.GRAB_PHOTO_ACTIVITY:
					photoGrabber.process(requestCode, resultCode, intent);
					return;
				case RequestCode.GRAB_MRDT_PHOTO_ACTIVITY:
					processMrdtResult(requestCode, resultCode, intent);
					return;
				case RequestCode.DISCLOSURE_LOCATION_ACTIVITY:
					processLocationPermissionResult(resultCode);
					return;
				case RequestCode.CHT_EXTERNAL_APP_ACTIVITY:
					processChtExternalAppResult(resultCode, intent);
					return;
				default:
					trace(this, "onActivityResult() :: no handling for requestCode=%s", requestCodeLabel);
			}
		} catch(Exception ex) {
			String action = intent == null ? null : intent.getAction();
			warn(ex, "Problem handling intent %s (%s) with requestCode=%s & resultCode=%s",
				intent, action, requestCodeLabel, resultCode);
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		boolean granted = grantResults.length > 0 && grantResults[0] == PERMISSION_GRANTED;

		if (requestCode == RequestCode.ACCESS_LOCATION_PERMISSION) {
			if (granted) {
				locationRequestResolved();
				return;
			}
			processGeolocationDeniedStatus();
			return;
		}

		if (requestCode == RequestCode.ACCESS_STORAGE_PERMISSION) {
			if (granted) {
				this.chtExternalAppHandler.resumeActivity();
				return;
			}
			trace(this, "ChtExternalAppHandler :: User rejected permission.");
			return;
		}
	}

//> ACCESSORS
	MrdtSupport getMrdtSupport() {
		return this.mrdt;
	}

	SmsSender getSmsSender() {
		return this.smsSender;
	}

	ChtExternalAppHandler getChtExternalAppLauncherActivity() {
		return this.chtExternalAppHandler;
	}

//> PUBLIC API
	public void evaluateJavascript(final String js) {
		evaluateJavascript(js, true);
	}

	public void evaluateJavascript(final String js, final boolean useLoadUrl) {
		int maxUrlSize = 2097100; // Maximum character limit supported for loading as url.

		if (useLoadUrl && js.length() <= maxUrlSize) {
			// `WebView.loadUrl()` seems to be significantly faster than `WebView.evaluateJavascript()` on Tecno Y4.
			container.post(() -> container.loadUrl("javascript:" + js, null));
		} else {
			container.post(() -> container.evaluateJavascript(js, IGNORE_RESULT));
		}
	}

	public void errorToJsConsole(String message, Object... extras) {
		String formatted = String.format(message, extras);
		String escaped = formatted.replace("'", "\\'");
		evaluateJavascript("console.error('" + escaped + "');");
	}

	public boolean isMigrationRunning() {
		return isMigrationRunning;
	}

	public void setMigrationRunning(boolean migrationRunning) {
		isMigrationRunning = migrationRunning;
	}

	public boolean getLocationPermissions() {
		boolean hasFineLocation = ContextCompat.checkSelfPermission(this, ACCESS_FINE_LOCATION) == PERMISSION_GRANTED;
		boolean hasCoarseLocation = ContextCompat.checkSelfPermission(this, ACCESS_COARSE_LOCATION) == PERMISSION_GRANTED;

		if (hasFineLocation && hasCoarseLocation) {
			trace(this, "getLocationPermissions() :: already granted");
			return true;
		}

		if (settings.hasUserDeniedGeolocation()) {
			trace(this, "getLocationPermissions() :: user has previously denied to share location");
			locationRequestResolved();
			return false;
		}

		trace(this, "getLocationPermissions() :: location not granted before, requesting access...");
		Intent intent = new Intent(this, RequestPermissionActivity.class);
		startActivityForResult(intent, RequestCode.DISCLOSURE_LOCATION_ACTIVITY);
		return false;
	}

	public void locationRequestResolved() {
		evaluateJavascript("window.CHTCore.AndroidApi.v1.locationPermissionRequestResolved();");
	}

//> PRIVATE HELPERS
	private void processChtExternalAppResult(int resultCode, Intent intentData) {
		String script = this.chtExternalAppHandler.processResult(resultCode, intentData);
		trace(this, "ChtExternalAppHandler :: Executing JavaScript: %s", script);
		evaluateJavascript(script);
	}

	private void processMrdtResult(int requestCode, int resultCode, Intent intent) {
		String js = mrdt.process(requestCode, resultCode, intent);
		trace(this, "Executing JavaScript: %s", js);
		evaluateJavascript(js);
	}

	private void processLocationPermissionResult(int resultCode) {
		if (resultCode == RESULT_OK) {
			ActivityCompat.requestPermissions(this, LOCATION_PERMISSIONS, RequestCode.ACCESS_LOCATION_PERMISSION);
		} else if (resultCode == RESULT_CANCELED) {
			processGeolocationDeniedStatus();
		}
	}

	private void processGeolocationDeniedStatus() {
		try {
			settings.setUserDeniedGeolocation();
			locationRequestResolved();
		} catch (SettingsException e) {
			error(e, "LocationPermissionRequest :: Error recording negative to access location");
		}
	}

	private void configureUserAgent() {
		String current = WebSettings.getDefaultUserAgent(this);
		container.getSettings().setUserAgentString(createUseragentFrom(current));
	}

	private void openSettings() {
		startActivity(new Intent(this, SettingsDialogActivity.class));
		finish();
	}

	private void browseTo(Uri url) {
		String urlToLoad = this.settings.getUrlToLoad(url);
		trace(this, "Pointing browser to: %s", redactUrl(urlToLoad));
		container.loadUrl(urlToLoad, null);
	}

	private void enableRemoteChromeDebugging() {
		WebView.setWebContentsDebuggingEnabled(true);
	}

	private void setUpUiClient(WebView container) {
		container.setWebChromeClient(new WebChromeClient() {
			@Override public boolean onConsoleMessage(ConsoleMessage cm) {
				if(!DEBUG) {
					return super.onConsoleMessage(cm);
				}

				trace(this, "onConsoleMessage() :: %s:%s | %s",
						cm.sourceId(),
						cm.lineNumber(),
						cm.message());
				return true;
			}
			@Override public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, WebChromeClient.FileChooserParams fileChooserParams){
				trace(this, "onShowFileChooser() :: webView: %s,filePathCallback: %s,fileChooserParams: %s", webView, filePathCallback, fileChooserParams);

				boolean capture = fileChooserParams.isCaptureEnabled();
				trace(this, "onShowFileChooser() capture :: %s", capture);
				String[] acceptTypes = fileChooserParams.getAcceptTypes();
				trace(this, "onShowFileChooser() acceptTypes :: %s", Arrays.toString(acceptTypes));

				if(!photoGrabber.canHandle(acceptTypes, capture)) {
					warn(this, "openFileChooser() :: No file chooser is currently implemented for \"accept\" value: %s", Arrays.toString(acceptTypes));
					return false;
				}

				trace(this, "onShowFileChooser() opening chooser");
				photoGrabber.chooser(filePathCallback, capture);
				return true;
			}
			@Override public void onGeolocationPermissionsShowPrompt(final String origin, final GeolocationPermissions.Callback callback) {
				callback.invoke(origin, true, true);
			}
		});
	}

	@SuppressLint("SetJavaScriptEnabled")
	private void enableJavascript(WebView container) {
		container.getSettings().setJavaScriptEnabled(true);

		MedicAndroidJavascript maj = new MedicAndroidJavascript(this);
		maj.setAlert(new Alert(this));

		maj.setActivityManager((ActivityManager) this.getSystemService(Context.ACTIVITY_SERVICE));

		maj.setConnectivityManager((ConnectivityManager) this.getSystemService(Context.CONNECTIVITY_SERVICE));

		container.addJavascriptInterface(maj, "medicmobile_android");
	}

	private void enableStorage(WebView container) {
		WebSettings settings = container.getSettings();
		settings.setDomStorageEnabled(true);
		settings.setDatabaseEnabled(true);
	}

	private void enableUrlHandlers(WebView container) {
		container.setWebViewClient(new UrlHandler(this, settings));
	}

	private void toast(String message) {
		if (message != null) {
			Toast.makeText(container.getContext(), message, Toast.LENGTH_LONG).show();
		}
	}

	private void registerRetryConnectionBroadcastReceiver() {
		BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
			@Override public void onReceive(Context context, Intent intent) {
				String action = intent.getAction();
				if (action.equals("retryConnection")) {
					// user fixed the connection and asked the app
					// to retry the load from the connection error activity
					evaluateJavascript("window.location.reload()", false);
				}
			}
		};
		registerReceiver(broadcastReceiver, new IntentFilter("retryConnection"));
	}

//> CLASSES
	public static class RequestCode {
		static final int ACCESS_LOCATION_PERMISSION = 100;
		static final int ACCESS_STORAGE_PERMISSION = 101;
		static final int CHT_EXTERNAL_APP_ACTIVITY = 102;
		static final int DISCLOSURE_LOCATION_ACTIVITY = 103;
		static final int GRAB_MRDT_PHOTO_ACTIVITY = 104;
		static final int GRAB_PHOTO_ACTIVITY = 105;

		static String requestCodeToString(int requestCode) {
			switch (requestCode) {
				case ACCESS_LOCATION_PERMISSION:
					return "ACCESS_LOCATION_PERMISSION";
				case ACCESS_STORAGE_PERMISSION:
					return "ACCESS_STORAGE_PERMISSION";
				case CHT_EXTERNAL_APP_ACTIVITY:
					return "CHT_EXTERNAL_APP_ACTIVITY";
				case DISCLOSURE_LOCATION_ACTIVITY:
					return "DISCLOSURE_LOCATION_ACTIVITY";
				case GRAB_MRDT_PHOTO_ACTIVITY:
					return "GRAB_MRDT_PHOTO_ACTIVITY";
				case GRAB_PHOTO_ACTIVITY:
					return "GRAB_PHOTO_ACTIVITY";
				default:
					return String.valueOf(requestCode);
			}
		}
	}
}
