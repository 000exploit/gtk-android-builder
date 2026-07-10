/* LocaleEnvProvider.java — bridge Android locale preferences to gettext.
 *
 * gettext-based native code (the GLib/GTK stack) picks its UI language from
 * the LANGUAGE / LANG environment variables, which Android never sets: bionic
 * has no real locale system, and the device language exists only on the Java
 * side. This provider runs before Application.onCreate() in the app process
 * and exports that Java-side knowledge into the environment, so "follow the
 * system language" works for any bundled gettext catalogs.
 *
 * It also handles language changes at runtime: GTK has no retranslation
 * mechanism (strings are copied into widgets at construction), so the only
 * honest move is a restart. Change-event based detection proved unreliable —
 * values read during the delivery flicker between empty, system-only and
 * real lists, and a process that exits in the background gets resurrected
 * cached, where configuration changes are no longer delivered at all. So the
 * check runs when an activity comes (back) to the screen instead: the state
 * is settled by then, only the final choice matters no matter how many times
 * the language changed meanwhile, and the app may legally start its own
 * relaunch intent. The activity declares locale in configChanges
 * (manifest.xsl) so a change doesn't pointlessly relaunch the native surface
 * before we do.
 *
 * A ContentProvider is used instead of an Application subclass so the GTK
 * glue's RuntimeApplication stays untouched; providers are initialized by the
 * framework before any application or activity code, which is early enough —
 * native main() only runs once the activity launches.
 */
package arpa.sp1rit.pixiewood;

import android.app.Activity;
import android.app.Application;
import android.app.LocaleManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.system.Os;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;

public final class LocaleEnvProvider extends ContentProvider {
	private static final String TAG = "LocaleEnvProvider";

	/* The list this process was started with; comparing against it detects
	 * both a real change and a native-side override (an in-app pin). */
	private String exported = null;

	@Override
	public boolean onCreate() {
		try {
			exportLocales();
			watchActivities();
		} catch (Exception e) {
			Log.w(TAG, "Failed to export the locale environment", e);
		}
		return true;
	}

	/* Whatever happened while this process was cached or dead — including
	 * several language changes in a row, of which only the final state
	 * matters — the moment an activity comes (back) to the screen is a
	 * settled, reliable time to compare the authoritative list against this
	 * process's export, and the app may legally start activities. */
	private void watchActivities() {
		Context app = getContext().getApplicationContext();
		if (!(app instanceof Application))
			return;
		((Application) app).registerActivityLifecycleCallbacks(
				new Application.ActivityLifecycleCallbacks() {
			@Override
			public void onActivityPreCreated(Activity activity, Bundle savedInstanceState) {
				// Before the native surface spins up, minimizing the flash.
				restartIfLanguageChanged(activity);
			}

			@Override
			public void onActivityResumed(Activity activity) {
				// Covers a live activity resuming without recreation (locale
				// is in the activity's configChanges).
				restartIfLanguageChanged(activity);
			}

			@Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
			@Override public void onActivityStarted(Activity activity) {}
			@Override public void onActivityPaused(Activity activity) {}
			@Override public void onActivityStopped(Activity activity) {}
			@Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
			@Override public void onActivityDestroyed(Activity activity) {}
		});
	}

	private void exportLocales() throws Exception {
		// The app's Configuration also reflects Android 13+ per-app language
		// preferences, which a plain Locale.getDefault() can miss.
		exported = languageList(getContext().getResources().getConfiguration());
		if (exported == null)
			return;

		// LANGUAGE carries the whole preference list (gettext tries each entry
		// in order); LANG names the primary locale for code that only reads the
		// classic variable. Never overwrite: a wrapper or debug harness that
		// set these did so deliberately.
		Os.setenv("LANGUAGE", exported, false);
		Os.setenv("LANG", exported.split(":")[0] + ".UTF-8", false);
	}

	/* The gettext-style priority list, or null if there are no usable
	 * locales. The Android 13+ per-app language choice is read straight from
	 * LocaleManager: it is the authoritative store, whereas the Configuration
	 * of this provider's context may not have the override applied yet —
	 * providers initialize before the framework configures the app context,
	 * which would silently reduce every export to the system language list.
	 * The Configuration follows as the fallback (and the system-list tail).
	 *
	 * An English entry cuts the list: it is always satisfiable — by an en
	 * catalog if one is bundled, by the msgids themselves otherwise — so
	 * nothing after it is reachable. Without the cut, picking English would
	 * silently fall through to the *next* preferred language whenever no
	 * en.mo exists (gettext can't "match" the source language; e.g. en-DK
	 * followed by the system's da-DK would come up Danish). */
	private String languageList(Configuration config) {
		ArrayList<String> names = new ArrayList<String>();
		boolean cut = false;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			try {
				LocaleManager manager = getContext().getSystemService(LocaleManager.class);
				if (manager != null) {
					LocaleList chosen = manager.getApplicationLocales();
					for (int i = 0; i < chosen.size() && !cut; i++)
						cut = append(names, chosen.get(i));
				}
			} catch (Exception e) {
				Log.w(TAG, "Failed to read the per-app locales", e);
			}
		}
		LocaleList locales = config.getLocales();
		for (int i = 0; i < locales.size() && !cut; i++)
			cut = append(names, locales.get(i));
		return names.isEmpty() ? null : String.join(":", names);
	}

	/* Returns whether the appended locale was English (the cut point). */
	private static boolean append(ArrayList<String> names, Locale locale) {
		String lang = locale.getLanguage();
		if (lang.isEmpty())
			return false;
		// java.util.Locale still reports the pre-1989 ISO 639 codes;
		// gettext catalogs are named with the modern ones.
		if (lang.equals("iw"))
			lang = "he";
		else if (lang.equals("in"))
			lang = "id";
		else if (lang.equals("ji"))
			lang = "yi";
		String country = locale.getCountry();
		String name = country.isEmpty() ? lang : (lang + "_" + country);
		if (!names.contains(name))
			names.add(name);
		return lang.equals("en");
	}

	private boolean restarting = false;

	private void restartIfLanguageChanged(Activity activity) {
		if (restarting || exported == null)
			return;
		String langs = languageList(activity.getResources().getConfiguration());
		if (langs == null || langs.equals(exported))
			return;
		try {
			// If native code overrode LANGUAGE (an in-app language pin), the
			// system list doesn't decide the UI language; restarting would be
			// disruption for no visible change.
			String current = Os.getenv("LANGUAGE");
			if (current != null && !current.equals(exported))
				return;
		} catch (Exception e) {
			// Can't tell; a restart is still the right default.
		}
		restarting = true;
		Log.i(TAG, "App language changed (" + exported + " -> " + langs
				+ "); relaunching translated");
		Intent launch = activity.getPackageManager()
				.getLaunchIntentForPackage(activity.getPackageName());
		if (launch != null) {
			launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
			activity.startActivity(launch);
		}
		System.exit(0);
	}

	/* This provider exists purely for its lifecycle callbacks. */

	@Override
	public Cursor query(Uri uri, String[] projection, String selection,
	                    String[] selectionArgs, String sortOrder) {
		return null;
	}

	@Override
	public String getType(Uri uri) {
		return null;
	}

	@Override
	public Uri insert(Uri uri, ContentValues values) {
		return null;
	}

	@Override
	public int delete(Uri uri, String selection, String[] selectionArgs) {
		return 0;
	}

	@Override
	public int update(Uri uri, ContentValues values, String selection,
	                  String[] selectionArgs) {
		return 0;
	}
}
