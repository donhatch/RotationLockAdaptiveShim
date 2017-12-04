package com.example.donhatch.rotationlockadaptiveshim;

// Info on how to force screen rotation for apps that request otherwise,
// using a transparent overlay:
//   http://stackoverflow.com/questions/14587085/how-can-i-globally-force-screen-orientation-in-android/14654302#answer-14654302
//   http://stackoverflow.com/questions/14587085/how-can-i-globally-force-screen-orientation-in-android/14654302#answer-14862852
//   http://stackoverflow.com/questions/10364815/how-does-rotation-locker-work
//   http://stackoverflow.com/questions/32241079/setting-orientation-globally-using-android-service
// And an app with source code that does it:  PerApp

import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.Toast;

public class TheService extends Service {

    private static final String TAG = "RLAS service";  // was "RotationLockAdaptiveShim service" but got warnings
    private static final int TINY_DELAY = 500;

    private static void CHECK(boolean condition) {
        if (!condition) {
            throw new AssertionError("CHECK failed");
        }
    }
    // return from plus or minus some multiple of 360 so it's as close as possible to closeTo
    private static double adjustDegrees(double from, double closeTo) {
        while (from < closeTo - 180.) from += 360.;
        while (from > closeTo + 180.) from -= 360.;
        return from;
    }
    private static double clamp(double x, double a, double b) {
        return x <= a ? a : x >= b ? b : x;
    }
    private static double pullDegreesAlmostTo(double oldDegrees, double almostNewDegrees, double slack)
    {
        oldDegrees = adjustDegrees(oldDegrees, almostNewDegrees);
        double answer = clamp(oldDegrees, almostNewDegrees - slack, almostNewDegrees + slack);
        if (answer >= 360.) {
            answer -= 360.;
        } else if (answer < 0.) {
            answer += 360.;
        }
        return answer;
    }


    public static TheService theRunningService = null;
    public boolean mHasBeenDestroyed = false;
    // omfg it has to be nonzero
    private final int AN_IDENTIFIER_FOR_THIS_NOTIFICATION_UNIQUE_WITHIN_THIS_APPLICATION = 1;

    int mVerboseLevel = 1; // 0: nothing, 1: major stuff, 2: every accelerometer event (lots)

    // These are static to avoid having to think about when the service isn't running.
    public static int mStaticDegrees = -1; // most recent value passed to onOrientationChanged listener of any TheService instance.
    public static double mStaticDegreesSmoothed = -1.; // most recent value passed to onOrientationChanged listener of any TheService instance.
    public static boolean mStaticDegreesIsValid = false;
    public static int mStaticClosestCompassPoint = -1; // means invalid
    private OrientationEventListener mOrientationEventListener;
    private Runnable mCleanupDialog = null;

    // http://stackoverflow.com/questions/14587085/how-can-i-globally-force-screen-orientation-in-android/14654302#answer-14862852
    private LinearLayout mOrientationChanger = null;  // has to be constructed when we have a context
    private int mOrientationChangerCurrentBackgroundColor;
    private WindowManager.LayoutParams mOrientationLayout;

    // We listen to these system settings, so these values should always be (pretty much) current
    private int mCurrentSystemSettingACCELEROMETER_ROTATION = -1; // CBB: actually could be a valid value
    private int mCurrentSystemSettingUSER_ROTATION = -1; // CBB: actually could be a valid value, though unlikely

    private void updateCurrentACCELEROMETER_ROTATION() {
        try {
            mCurrentSystemSettingACCELEROMETER_ROTATION = Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION);
            if (mVerboseLevel >= 1) Log.i(TAG, "              Settings.System.ACCELEROMETER_ROTATION is now "+mCurrentSystemSettingACCELEROMETER_ROTATION);
        } catch (Settings.SettingNotFoundException e) {
            if (mVerboseLevel >= 1) Log.i(TAG, "              Settings.System.ACCELEROMETER_ROTATION was not found!?");
        }
    }

    private void updateCurrentUSER_ROTATION() {
        try {
            mCurrentSystemSettingUSER_ROTATION = Settings.System.getInt(getContentResolver(), Settings.System.USER_ROTATION);
            if (mVerboseLevel >= 1) Log.i(TAG, "              Settings.System.USER_ROTATION is now "+mCurrentSystemSettingUSER_ROTATION);
        } catch (Settings.SettingNotFoundException e) {
            if (mVerboseLevel >= 1) Log.i(TAG, "              Settings.System.USER_ROTATION was not found!?");
        }
    }

    private ContentObserver mAccelerometerRotationObserver = new ContentObserver(new Handler()) {
        // Per https://developer.android.com/reference/android/database/ContentObserver.html (probably not necessary though)
        @Override public void onChange(boolean selfChange) { onChange(selfChange, null); }
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            Log.i(TAG, "            in TheService mAccelerometerRotationObserver onChange(selfChange="+selfChange+", uri="+uri+")");
            updateCurrentACCELEROMETER_ROTATION();
            if (mStaticWhackAMole) {
                if (mCurrentSystemSettingACCELEROMETER_ROTATION != 0) {  // i.e. actually nonzero, or not found (which probably never happens)
                    Log.i(TAG, "              WHACK!");
                    Toast.makeText(TheService.this, "WHACK! system autorotate got turned on, turning it back off", Toast.LENGTH_SHORT).show();
                    try {
                        Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0);
                        // Do this here in addition to in listener, since it seems to be best practice
                        // (judging from the analogous situation for USER_ROTATION)
                        mCurrentSystemSettingACCELEROMETER_ROTATION = 0;
                    } catch (SecurityException e) {
                        // XXX dup code
                        if (mVerboseLevel >= 1) Log.i(TAG, "              Oh no, can't set system settings-- were permissions revoked?");
                        Toast.makeText(TheService.this, " Oh no, can't set system settings-- were permissions revoked?\nHere, please grant the permission.", Toast.LENGTH_LONG).show();
                        Intent grantIntent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                        grantIntent.setData(Uri.parse("package:"+getPackageName()));
                        if (mVerboseLevel >= 1) Log.i(TAG, "                  grantIntent = "+grantIntent);
                        if (mVerboseLevel >= 1) Log.i(TAG, "                  calling startActivity with ACTION_MANAGE_WRITE_SETTINGS");
                        startActivity(grantIntent);
                        if (mVerboseLevel >= 1) Log.i(TAG, "                  returned from startActivity with ACTION_MANAGE_WRITE_SETTINGS (but still didn't set ACCELEROMETER_ROTATION like we wanted!)");
                        // CBB: still didn't write the value; not sure we can do anything better
                        // since permission might not have actually been granted.
                    }
                }
            }
            Log.i(TAG, "            out TheService mAccelerometerRotationObserver onChange(selfChange="+selfChange+", uri="+uri+")");
        }
    };  // mAccelerometerRotationObserver
    private ContentObserver mUserRotationObserver = new ContentObserver(new Handler()) {
        // Per https://developer.android.com/reference/android/database/ContentObserver.html (probably not necessary though)
        @Override public void onChange(boolean selfChange) { onChange(selfChange, null); }
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            Log.i(TAG, "            in TheService mUserRotationObserver onChange(selfChange="+selfChange+", uri="+uri+")");
            // Potential bug if we *only* set mCurrentSystemSettingUSER_ROTATION here
            // and not when we set it:
            //     User rotates phone to 0
            //         -> mStaticClosestCompassPoint changes to 0
            //            USER_ROTATION is changed from 90 to 0
            //            (but we don't get the callback yet so mCurrentSystemSettingUSER_ROTATION is still 90)
            //     User rotates phone to 270
            //         -> mStaticClosestCompassPoint changes to 270
            //            this should cause USER_ROTATION to be changed to 90,
            //            but we see mCurrentSystemSettingUSER_ROTATION is already 90
            //            so we do nothing!  oh no!
            updateCurrentUSER_ROTATION();
            Log.i(TAG, "            out TheService mUserRotationObserver onChange(selfChange="+selfChange+", uri="+uri+")");
        }
    };  // mUserRotationObserver

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mVerboseLevel >= 1) Log.i(TAG, "        in onReceive(intent.getAction()="+intent.getAction()+")");
            if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                if (mVerboseLevel >= 1) Log.i(TAG, "          ACTION_SCREEN_OFF: disabling orientation event listener");
                mOrientationEventListener.disable();
            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                // same thing we do on create (dup code)
                if (mOrientationEventListener.canDetectOrientation() == true) {
                    if (mVerboseLevel >= 1) Log.i(TAG, "          ACTION_SCREEN_ON and can detect orientation, enabling orientation event listener");
                    mOrientationEventListener.enable();
                } else {
                    if (mVerboseLevel >= 1) Log.i(TAG, "          ACTION_SCREEN_ON but cannot detect orientation, disabling orientation event listener");
                    mOrientationEventListener.disable();
                }
            } else if (intent.getAction().equals(Intent.ACTION_USER_PRESENT)) {
                if (mVerboseLevel >= 1) Log.i(TAG, "          ACTION_USER_PRESENT");
                // XXX TODO: figure out whether there's something meaningful to do here
            } else {
                // This shouldn't happen
                Log.i(TAG, "          received unexpected broadcast: "+intent.getAction()+" (this shouldn't happen)");
                CHECK(false);
            }
            if (mVerboseLevel >= 1) Log.i(TAG, "        out onReceive(intent.getAction()="+intent.getAction()+")");
        };
    };  // mBroadcastReceiver


    public static boolean mStaticWhackAMole = true; // TODO: make this a shared preference? the activity is the one who sets this
    public static boolean mStaticAutoRotate = true; // TODO: make this a shared preference? the activity is the one who sets this
    public static boolean mStaticPromptFirst = true; // TODO: make this a shared preference? the activity is the one who sets this
    public static boolean mStaticOverride = true; // TODO: make this a shared preference? the activity is the one who sets this

    // this one has a public setter/getter
    private static boolean mStaticRed = false; // TODO: make this a shared preference? the activity is the one who sets this

    // XXX TODO: call it SurfaceRotationConstant instead?
    private static int closestCompassPointToUserRotation(int closestCompassPoint) {
        switch (closestCompassPoint) {
            case 0: return Surface.ROTATION_0;
            case 90: return Surface.ROTATION_270;
            case 180: return Surface.ROTATION_180;
            case 270: return Surface.ROTATION_90;
            default: CHECK(false); return -1;  // shouldn't happen
        }
    }  // closestCompassPointToUserRotation

    private static int closestCompassPointToScreenOrientationConstant(int closestCompassPoint) {
        switch (closestCompassPoint) {
            case 0: return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            case 90: return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
            case 180: return ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
            case 270: return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            default: CHECK(false); return -1;  // shouldn't happen
        }
    }  // closestCompassPointToScreenOrientationConstant

    // Used as value of System.Settings.USER_ROTATION
    public static String surfaceRotationConstantToString(int surfaceRotationConstant) {
        switch (surfaceRotationConstant) {
            case Surface.ROTATION_0: return "ROTATION_0";
            case Surface.ROTATION_90: return "ROTATION_90";
            case Surface.ROTATION_180: return "ROTATION_180";
            case Surface.ROTATION_270: return "ROTATION_270";
            default: return "[unknown surface rotation constant "+surfaceRotationConstant+"]";
        }
    }  // surfaceRotationConstantToString


    // There are two different namespaces here:
    //   1. https://developer.android.com/reference/android/R.attr.html#screenOrientation
    //      R.attr, "the screen orientation attribute".
    //      ActivityInfo.SCREEN_ORIENTATION_BEHIND, etc.
    //
    //   2. https://developer.android.com/reference/android/content/pm/ActivityInfo.html#screenOrientation
    //      setRequestedOrientation(), getRequestedOrientation(), Activity.screenOrientation
    //      behind, etc.
    // But the values are the same!?  Weird.
    public static String screenOrientationConstantToString(int screenOrientationConstant) {
        switch (screenOrientationConstant) {
            case ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED: return "SCREEN_ORIENTATION_UNSPECIFIED";
            case ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE: return "SCREEN_ORIENTATION_LANDSCAPE";
            case ActivityInfo.SCREEN_ORIENTATION_PORTRAIT: return "SCREEN_ORIENTATION_PORTRAIT";
            case ActivityInfo.SCREEN_ORIENTATION_USER: return "SCREEN_ORIENTATION_USER";
            case ActivityInfo.SCREEN_ORIENTATION_BEHIND: return "SCREEN_ORIENTATION_BEHIND";
            case ActivityInfo.SCREEN_ORIENTATION_SENSOR: return "SCREEN_ORIENTATION_SENSOR";
            case ActivityInfo.SCREEN_ORIENTATION_NOSENSOR: return "SCREEN_ORIENTATION_NOSENSOR";
            case ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE: return "SCREEN_ORIENTATION_SENSOR_LANDSCAPE";
            case ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT: return "SCREEN_ORIENTATION_SENSOR_PORTRAIT";
            case ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE: return "SCREEN_ORIENTATION_REVERSE_LANDSCAPE";
            case ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT: return "SCREEN_ORIENTATION_REVERSE_PORTRAIT";
            case ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR: return "SCREEN_ORIENTATION_FULL_SENSOR";
            case ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE: return "SCREEN_ORIENTATION_USER_LANDSCAPE";
            case ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT: return "SCREEN_ORIENTATION_USER_PORTRAIT";
            case ActivityInfo.SCREEN_ORIENTATION_FULL_USER: return "SCREEN_ORIENTATION_FULL_USER";
            case ActivityInfo.SCREEN_ORIENTATION_LOCKED: return "SCREEN_ORIENTATION_LOCKED";
            default: return "[unknown screen orientation constant "+screenOrientationConstant+"]";
        }
    }
    // Actuallly one more namespace:
    //   (3) https://developer.android.com/reference/android/content/res/Configuration.html#orientation
    //   Configuration.orientation
    //   Configuration.ORIENTATION_PORTRAIT (1) or Configuration.ORIENTATION_LANDSCAPE (2)
    public static String orientationConstantToString(int orientationConstant) {
        switch (orientationConstant) {
            case Configuration.ORIENTATION_PORTRAIT: return "ORIENTATION_PORTRAIT";
            case Configuration.ORIENTATION_LANDSCAPE: return "ORIENTATION_LANDSCAPE";
            default: return "[unknown orientation constant "+orientationConstant+"]";
        }
    }

    public static String motionEventActionMaskedConstantToString(int motionEventActionMaskedConstant) {
        switch (motionEventActionMaskedConstant) {
            case MotionEvent.ACTION_BUTTON_PRESS: return "ACTION_BUTTON_PRESS";
            case MotionEvent.ACTION_BUTTON_RELEASE: return "ACTION_BUTTON_RELEASE";
            case MotionEvent.ACTION_CANCEL: return "ACTION_CANCEL";
            case MotionEvent.ACTION_DOWN: return "ACTION_DOWN";
            case MotionEvent.ACTION_HOVER_ENTER: return "ACTION_HOVER_ENTER";
            case MotionEvent.ACTION_HOVER_EXIT: return "ACTION_HOVER_EXIT";
            case MotionEvent.ACTION_HOVER_MOVE: return "ACTION_HOVER_MOVE";
            case MotionEvent.ACTION_MOVE: return "ACTION_MOVE";
            case MotionEvent.ACTION_OUTSIDE: return "ACTION_OUTSIDE";
            case MotionEvent.ACTION_POINTER_DOWN: return "ACTION_POINTER_DOWN";
            case MotionEvent.ACTION_POINTER_UP: return "ACTION_POINTER_UP";
            case MotionEvent.ACTION_SCROLL: return "ACTION_SCROLL";
            case MotionEvent.ACTION_UP: return "ACTION_UP";
            default: return "[unknown motionEvent actionMasked constant "+motionEventActionMaskedConstant+"]";
        }
    }  // motionEventActionMaskedConstantToString

    public TheService() {
        if (mVerboseLevel >= 1) Log.i(TAG, "                    in TheService ctor");
        if (mVerboseLevel >= 1) Log.i(TAG, "                    out TheService ctor");
    }

    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Not implemented");
    }

    public class BetterToast {
/*
#ifdef NOTYET
        // This class allows making a toast of any given duration.
        // CAVEAT: it will interact well with other BetterToasts, but not regular toasts.
        // In particular, if a regular toast is being displayed,
        // this toast will get delayed in starting... but not in ending,
        // so it might not get displayed at all.
        private static BetterToast first = null; // the currently running one
        private static BetterToast last = null;
        private Toast toast;
        private long millis;
        private BetterToast prev;
        private BetterToast next;
        private BetterToast(Toast toast, millis) {
            this.toast = toast;
            this.millis = millis;
            this.prev = null;
            this.next = null;
        }
        public static BetterToast makeText(Context context, String text, long millis) {
            BetterToast betterToast = new BetterToast(Toast.makeText(context, text, LENGTH_LONG), millis);
        }
        public void show() {
            if (this == first) {
                // extend it from now
                this.toast.show();
                ... set a timeout to either cancel it or extend it ...
            } else if (first == null) {
                // Start it as the one and only
                CHECK(this.prev == null);
                CHECK(this.next == null);
                first = last = this;
                this.toast.show();
                ... set a timeout to either cancel it or extend it ...
            } else {
                // Remove it from list if it's there, and append
                if (this.next != null) {
                    this.next.prev = this.prev;
                }
                if (this.prev != null) {
                    this.prev.next = this.next;
                }
                CHECK(last.next == null);
                CHECK(this.prev == null);
                last.next = this;
                this.prev = last;
                last = this;
            }
        }
        public void cancel() {
        }
    #endif // NOTYET
    */
    }

    // Show toast for some length of time <= LENGTH_LONG (3500 millis).
    // BUG: if some other toast is up already, this one will get delayed in starting... but not in ending!  Argh.
    private Toast showToast(Context ctx, String text, long millis) {
        // LENGTH_SHORT is 2000 millis
        // LENGTH_LONG is 3500 millis  (not sure I believe that though-- seems like at least 6000)
        final Toast toast = Toast.makeText(ctx, text, Toast.LENGTH_LONG);
        toast.show();

        Handler handler = new Handler();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                toast.cancel();
            }
        }, millis);
        return toast;
    }

    @Override
    public void onCreate() {
        if (mVerboseLevel >= 1) Log.i(TAG, "                        in TheService.onCreate");
        if (mVerboseLevel >= 1) Log.i(TAG, "                          Build.VERSION.SDK_INT = "+Build.VERSION.SDK_INT);  // what's on the current machine at runtime
        if (mVerboseLevel >= 1) Log.i(TAG, "                          Build.VERSION_CODES.O = "+Build.VERSION_CODES.O);
        if (mVerboseLevel >= 1) Log.i(TAG, "                          getApplicationContext().getApplicationInfo().targetSdkVersion = "+getApplicationContext().getApplicationInfo().targetSdkVersion);
        // note that in real apps installed from store, I think it's always true that runtime version <= targetSdkVersion

        if (theRunningService != null) {
            // XXX do this via a Notification, I think
            // XXX have I decided this never happens?  Not sure.
            throw new AssertionError("TheService.onCreate called when there's already an existing service!");
        }
        TheService.theRunningService = this;

        if (false) // set to true to experiment with renewing toasts to show it works.
        {
            final Toast toast = showToast(this, "HEY!", 20000);

            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    Log.i(TAG, "renewing?");
                    toast.setDuration(Toast.LENGTH_LONG);
                    toast.show();
                }
            }, 2000);
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    Log.i(TAG, "renewing again?");
                    toast.setDuration(Toast.LENGTH_LONG);
                    toast.show();
                }
            }, 4000);
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    Log.i(TAG, "renewing yet again?");
                    toast.setDuration(Toast.LENGTH_LONG);
                    toast.show();
                }
            }, 6000);
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    Log.i(TAG, "renewing yet again, again?");
                    toast.setDuration(Toast.LENGTH_LONG);
                    toast.show();
                }
            }, 8000);
        }

        // TODO: cancel previous toast if any
        showToast(this, "Service Created", 1000); // it's about to change to "Service Started"

        updateCurrentACCELEROMETER_ROTATION();
        updateCurrentUSER_ROTATION();
        getContentResolver().registerContentObserver(Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, mAccelerometerRotationObserver);
        getContentResolver().registerContentObserver(Settings.System.getUriFor(Settings.System.USER_ROTATION), false, mUserRotationObserver);

        // http://stackoverflow.com/questions/14587085/how-can-i-globally-force-screen-orientation-in-android/14654302#answer-14862852
        // and more specifically helpful code from the PerApp source code here: http://code.google.com/p/perapp
        // Needs permission android.permission.SYSTEM_ALERT_WINDOW (XXX and double-opt-in, I think?)
        mOrientationChanger = new LinearLayout(this);
        mOrientationChanger.setVisibility(View.GONE);
        mOrientationChangerCurrentBackgroundColor = (mStaticRed ? 0x44ff0000 : 0x00000000); // faint translucent red color if mStaticRed
        mOrientationChanger.setBackgroundColor(mOrientationChangerCurrentBackgroundColor);

        // Note, prior to api level 26, I used TYPE_SYSTEM_OVERLAY, which worked.
        // But that's now deprecated, and with targetSdkLevel>=26, it makes the addView crash.
        // So now I have to use TYPE_APPLICATION_OVERLAY+FLAG_NOT_TOUCHABLE instead, which seems to work.
        // (the flag is apparently necessary to get touchthrough).
        // Note that for TYPE_APPLICATION_OVERLAY to be defined requires compileSdkLevel>=26,
        // but it works (on *runtime* level>=26, anyway) even if targetSdkLevel=25.
        int type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY; // works for both now that I figured out the FLAG_NOT_TOUCHABLE thing
        int flags = 0;
        flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; // required in order for touchthrough to work at all
        flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; // required in order for playstore's keyboard to function when under this overlay (wtf!?)
        //flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL // irrelevant since FLAG_NOT_FOCUSABLE is set

        mOrientationLayout = new WindowManager.LayoutParams(
            /*_type=*/type,
            /*_flags=*/flags,
            /*_format=*/PixelFormat.RGBA_8888);
        CHECK(mOrientationLayout.screenOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        {
            WindowManager windowManager = (WindowManager)getSystemService(Service.WINDOW_SERVICE);
            if (mVerboseLevel >= 1) Log.i(TAG, "                          adding view mOrientationchanger");
            windowManager.addView(mOrientationChanger, mOrientationLayout);
            if (mVerboseLevel >= 1) Log.i(TAG, "                          added view mOrientationchanger");
            CHECK(mOrientationChanger.getVisibility() == View.GONE);
        }
        mOrientationEventListener = new OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
            @Override
            public void onOrientationChanged(int degrees) {
                if (mVerboseLevel >= 2) Log.i(TAG, "        in onOrientationChanged(degrees="+degrees+")");
                if (degrees == OrientationEventListener.ORIENTATION_UNKNOWN) { // -1
                    if (mVerboseLevel == 1) Log.i(TAG, "        in onOrientationChanged(degrees="+degrees+")");
                    if (mVerboseLevel >= 1) Log.i(TAG, "          (not doing anything)");
                    if (mVerboseLevel == 1) Log.i(TAG, "        out onOrientationChanged(degrees="+degrees+")");
                } else {
                    boolean closestCompassPointChanging = false;
                    if (mStaticClosestCompassPoint == -1) {
                        closestCompassPointChanging = true;
                    } else {
                        int distanceFromPreviousClosestCompassPoint = Math.abs(degrees - mStaticClosestCompassPoint);
                        if (distanceFromPreviousClosestCompassPoint > 180)
                            distanceFromPreviousClosestCompassPoint = Math.abs(distanceFromPreviousClosestCompassPoint - 360);
                        if (mVerboseLevel >= 2) Log.i(TAG, "          old mStaticClosestCompassPoint = " + mStaticClosestCompassPoint);
                        if (mVerboseLevel >= 2) Log.i(TAG, "          distanceFromPreviousClosestCompassPoint = " + distanceFromPreviousClosestCompassPoint);


                        // empirically, when using ACCELEROMETER_ROTATION, changes happen on:
                        //      67->68   (22.5)
                        //      24->23   (23.5)
                        //      293->292 (22.5)
                        //      336->337 (23.5)
                        // so hysteresis is 22.5 or 23.5.
                        // also, there's a bit of a delay, so you can rotate back and forth and it doesn't do anything.
                        double hysteresis = 22.5;
                        closestCompassPointChanging = distanceFromPreviousClosestCompassPoint > 45+hysteresis;
                    }

                    if (!closestCompassPointChanging) {
                        if (mVerboseLevel >= 2) Log.i(TAG, "          (device physical orientation quadrant didn't change)");
                    } else {
                        if (mVerboseLevel == 1) Log.i(TAG, "        in onOrientationChanged(degrees="+degrees+")"); // upgrade verbosity threshold from 2 to 1
                        int oldClosestCompassPoint = mStaticClosestCompassPoint;
                        int newClosestCompassPoint = degrees < 45 ? 0 : degrees < 135 ? 90 : degrees < 225 ? 180 : degrees < 315 ? 270 : 0;
                        mStaticClosestCompassPoint = newClosestCompassPoint;
                        CHECK(mStaticClosestCompassPoint != -1);

                        {
                            Intent intent = new Intent("mStaticClosestCompassPoint changed");
                            intent.putExtra("old mStaticClosestCompassPoint", oldClosestCompassPoint);
                            intent.putExtra("new mStaticClosestCompassPoint", newClosestCompassPoint);
                            LocalBroadcastManager.getInstance(TheService.this).sendBroadcast(intent);
                        }

                        // TODO: maybe move this more outward?
                        if (mCleanupDialog != null) {
                            mCleanupDialog.run();
                            mCleanupDialog = null;
                        }

                        if (mStaticAutoRotate) {
                            if (closestCompassPointToUserRotation(newClosestCompassPoint) == mCurrentSystemSettingUSER_ROTATION) {
                                if (mVerboseLevel == 1) Log.i(TAG, "          (USER_ROTATION="+surfaceRotationConstantToString(mCurrentSystemSettingUSER_ROTATION)+" is already correct)");
                            } else {
                                if (!mStaticPromptFirst) {
                                    if (mVerboseLevel >= 1) Log.i(TAG, "          calling doTheAutoRotateThing");
                                    doTheAutoRotateThingNow();
                                    if (mVerboseLevel >= 1) Log.i(TAG, "          returned from doTheAutoRotateThing");
                                } else {
                                    if (true) {
                                        // https://developer.android.com/guide/topics/ui/dialogs.html
                                        // For custom layout, use setView on the builder:
                                        // but all the examples in the world use xml layouts. what a huge waste of time.
                                        // maybe this one uses a view?
                                        // https://www.codota.com/android/methods/android.app.AlertDialog.Builder/setView
                                        // Maybe this has something?  http://iserveandroid.blogspot.com/2011/04/how-to-dismiss-your-non-modal-dialog.html

                                        if (mVerboseLevel == 1) Log.i(TAG, "          attempting to pop up an AlertDialog");

                                        // Don't use an AlertDialog.Builder, since that's incompatible with custom onTouchEvent.
                                        // (TODO:  although... could I use a View.OnTouchListener instead?)
                                        final AlertDialog alertDialog = new AlertDialog(TheService.this) {
                                            @Override
                                            public boolean onTouchEvent(MotionEvent motionEvent) {
                                                if (mVerboseLevel == 1) Log.i(TAG, "            in alertDialog onTouchEvent");
                                                if (mVerboseLevel == 1) Log.i(TAG, "              motionEvent.getActionMasked()="+motionEventActionMaskedConstantToString(motionEvent.getActionMasked()));
                                                if (motionEvent.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                                                    if (mVerboseLevel == 1) Log.i(TAG, "              touch outside dialog! cancelling");
                                                    if (mCleanupDialog != null) {
                                                      mCleanupDialog.run();
                                                      mCleanupDialog = null;
                                                    }
                                                } else {
                                                    if (mVerboseLevel == 1) Log.i(TAG, "              touch inside dialog; ignoring");
                                                }
                                                if (mVerboseLevel == 1) Log.i(TAG, "            out alertDialog onTouchEvent");
                                                // I think returning true is supposed to mean "consume", i.e.
                                                // don't pass the event to subsequent listeners or parent or "next level down",
                                                // but I don't observe it making any difference--
                                                // what we see as ACTION_OUTSIDE has an effect
                                                // on the activity underneath the dialog,
                                                // and touching inside the dialog does *not* affect the activity underneath,
                                                // regardless of whether we return true or false here.
                                                return false;
                                            }
                                        };
                                        alertDialog.setTitle("Rotate the screen?");
                                        alertDialog.setMessage("3...");
                                        alertDialog.setButton(AlertDialog.BUTTON_NEUTRAL, "Yes and don't ask again", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int id) {
                                                if (mVerboseLevel == 1) Log.i(TAG, "            in alertDialog neutral button onClick");

                                                // Same as yes, but turn off mStaticPromptFirst
                                                mStaticPromptFirst = false;
                                                Intent intent = new Intent("mStaticPromptFirst changed");
                                                intent.putExtra("new mStaticPromptFirst", mStaticPromptFirst);
                                                if (mVerboseLevel == 1) Log.i(TAG, "              sending \"mStaticPromptFirst changed\" broadcast");
                                                LocalBroadcastManager.getInstance(TheService.this).sendBroadcast(intent);
                                                if (mVerboseLevel == 1) Log.i(TAG, "              sent \"mStaticPromptFirst changed\" broadcast");
                                                if (mCleanupDialog != null) {
                                                  mCleanupDialog.run();
                                                  mCleanupDialog = null;
                                                }
                                                doTheAutoRotateThingNow();
                                                if (mVerboseLevel == 1) Log.i(TAG, "            out alertDialog neutral button onClick");
                                            }
                                        });
                                        alertDialog.setButton(AlertDialog.BUTTON_NEGATIVE, "No", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int id) {
                                                if (mVerboseLevel == 1) Log.i(TAG, "            in alertDialog negative button onClick");
                                                // XXX is this evidence for why it's good to always delay?
                                                if (mCleanupDialog != null) {
                                                  mCleanupDialog.run();
                                                  mCleanupDialog = null;
                                                }
                                                if (mVerboseLevel == 1) Log.i(TAG, "            out alertDialog negative button onClick");
                                            }
                                        });
                                        alertDialog.setButton(AlertDialog.BUTTON_POSITIVE, "Yes", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int id) {
                                                if (mVerboseLevel == 1) Log.i(TAG, "            in alertDialog positive button onClick");
                                                // XXX is this evidence for why it's good to always delay?
                                                if (mCleanupDialog != null) {
                                                  mCleanupDialog.run();
                                                  mCleanupDialog = null;
                                                }
                                                doTheAutoRotateThingNow();
                                                if (mVerboseLevel == 1) Log.i(TAG, "            out alertDialog positive button onClick");
                                            }
                                        });


                                        Window alertDialogWindow = alertDialog.getWindow();

                                        // This setType is required when context=TheService.this,
                                        // otherwise the show() gives WindowManager.BadTokenException.
                                        // prior to targetSdkVersion=26, I used TYPE_SYSTEM_ALERT, but that doesn't work any more;
                                        // the new thing is TYPE_APPLICATION_OVERLAY instead.
                                        alertDialogWindow.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);

                                        // Kill the dimming of the activity or whatever's behind it
                                        alertDialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                                        // Kill the drop shadow too
                                        //alertDialogWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

                                        // Trying advice in http://iserveandroid.blogspot.com/2011/04/how-to-dismiss-your-non-modal-dialog.html ...
                                        // Make it so user can interact with rest of screen
                                        alertDialogWindow.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
                                        // And so that we get the onTouchEvent first, when a touch comes on rest of screen
                                        alertDialogWindow.addFlags(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);


                                        try {
                                            alertDialog.show();
                                        } catch (WindowManager.BadTokenException e) {
                                            // This happens if I omit android.permission.SYSTEM_ALERT_WINDOW from AndroidManifest, or if user revoke or didn't grant "Draw over other apps".
                                            // TODO: need to do the double-opt-in dance here, similar to the WRITE_SETTINGS permission
                                            // TODO: also fails if I try to pop up the dialog, on targetSdkLevel>=26.  ah, it's because TYPE_SYSTEM_ALERT is deprecated for non-system apps, use TYPE_APPLICATION_OVERLAY instead?
                                            CHECK(false);
                                        }
                                        // Make it expire in 3 seconds.
                                        final Handler handler = new Handler();
                                        final Runnable runnable = new Runnable() {
                                            @Override
                                            public void run() {
                                                if (mVerboseLevel == 1) Log.i(TAG, "            in run: prompt expired; canceling alert dialog");
                                                alertDialog.cancel();
                                                mCleanupDialog = null;
                                                if (mVerboseLevel == 1) Log.i(TAG, "            out run: prompt expired; cancelled alert dialog");
                                            }
                                        };
                                        handler.postDelayed(runnable, 3*1000);

                                        CHECK(mCleanupDialog == null);
                                        mCleanupDialog = new Runnable() {
                                            @Override
                                            public void run() {
                                                if (mVerboseLevel == 1) Log.i(TAG, "            cleaning up previous dialog");
                                                alertDialog.cancel();
                                                handler.removeCallbacks(runnable); // ok if it wasn't scheduled
                                                if (mVerboseLevel == 1) Log.i(TAG, "            cleaned up previous dialog");
                                            }
                                        };

                                        // Hacky countdown in the dialog.
                                        // These don't get cleaned up by mCleanupDialog, but it doesn't matter; they don't hurt anything.
                                        new Handler().postDelayed(new Runnable() { @Override public void run() { alertDialog.setMessage("2..."); } }, 1*1000);
                                        new Handler().postDelayed(new Runnable() { @Override public void run() { alertDialog.setMessage("1..."); } }, 2*1000);

                                        if (mVerboseLevel == 1) Log.i(TAG, "          attempted to pop up an AlertDialog");
                                    }
                                    if (false) {
                                        if (mVerboseLevel == 1) Log.i(TAG, "          attempting to pop up a semitransparent icon!");
                                        // Pop up a semitransparent icon for at most 3 (or maybe configurable) seconds.
                                        // If user taps it within the 3 seconds, call doTheAutoRotateThingNow(), otherwise disappear it.
                                        // http://stackoverflow.com/questions/7678356/launch-popup-window-from-service
                                        final View myView = new View(TheService.this) {
                                            private Paint mPaint = new Paint() {{
                                                setTextSize(100);
                                                setARGB(200, 200, 200, 200);
                                            }};

                                            @Override
                                            protected void onDraw(Canvas canvas) {
                                                if (mVerboseLevel == 1) Log.i(TAG, "            in onDraw");
                                                super.onDraw(canvas);
                                                canvas.drawText("test test test", 0, 100, mPaint);
                                                if (mVerboseLevel == 1) Log.i(TAG, "            out onDraw");
                                            }

                                            @Override
                                            protected void onAttachedToWindow() {
                                                if (mVerboseLevel == 1) Log.i(TAG, "            in onAttachedToWindow");
                                                super.onAttachedToWindow();
                                                if (mVerboseLevel == 1) Log.i(TAG, "            out onAttachedToWindow");
                                            }

                                            @Override
                                            protected void onDetachedFromWindow() {
                                                if (mVerboseLevel == 1) Log.i(TAG, "            in onDetachedFromWindow");
                                                super.onDetachedFromWindow();
                                                if (mVerboseLevel == 1) Log.i(TAG, "            out onDetachedFromWindow");
                                            }

                                            @Override
                                            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                                                if (mVerboseLevel == 1) Log.i(TAG, "            in onMeasure");
                                                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                                                if (mVerboseLevel == 1) Log.i(TAG, "            out onMeasure");
                                            }
                                        };

                                        WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(
                                            /*w=*/WindowManager.LayoutParams.MATCH_PARENT,
                                            /*h=*/150,
                                            /*xpos=*/10,
                                            /*ypos=*/10,
                                            /*_type=*/WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,

                                            /*
                                            // I think doc says FLAG_NOT_TOUCH_MODAL only matters if not FLAG_NOT_FOCUSABLE ...
                                            / *_flags=* /WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                                            */

                                            /*_flags=*/WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, // allow pointer events outside of the window to be sent to the windows behind it

                                            /*_format=*/PixelFormat.TRANSLUCENT);
                                        layoutParams.gravity = Gravity.CENTER;
                                        layoutParams.setTitle("Window test");
                                        final WindowManager windowManager = (WindowManager)getSystemService(WINDOW_SERVICE); // there's no getWindowManager() in a service
                                        try {
                                             windowManager.addView(myView, layoutParams);
                                        } catch (WindowManager.BadTokenException e) {
                                             // This happens if I omit android.permission.SYSTEM_ALERT_WINDOW from AndroidManifest
                                             // TODO: needs the double-opt-in dance here, similar for the WRITE_SETTINGS permission
                                             CHECK(false);
                                        }

                                        // Make it expire in 3 seconds.
                                        new Handler().postDelayed(new Runnable() {
                                            @Override
                                            public void run() {
                                                if (mVerboseLevel == 1) Log.i(TAG, "            in run: prompt expired");
                                                windowManager.removeView(myView);
                                                if (mVerboseLevel == 1) Log.i(TAG, "            out run: prompt expired");
                                            }
                                        }, 3*1000);

                                        if (mVerboseLevel == 1) Log.i(TAG, "          attempted to pop up a semitransparent icon!");
                                    }
                                }
                            }
                        }

                        if (mVerboseLevel >= 1) Log.i(TAG, "          new mStaticClosestCompassPoint = " + mStaticClosestCompassPoint);
                        if (mVerboseLevel == 1) Log.i(TAG, "        out onOrientationChanged(degrees="+degrees+")"); // upgrade verbosity threshold from 2 to 1
                    }
                }

                // XXX ARGH! I think this should be done *before* the closestCompassPoint determination?
                int oldDegrees = mStaticDegrees;
                double oldDegreesSmoothed = mStaticDegreesSmoothed;

                double slack = 2.5;
                int newDegrees = degrees;
                double newDegreesSmoothed = pullDegreesAlmostTo(oldDegreesSmoothed, (double)newDegrees, slack);

                mStaticDegrees = newDegrees;
                mStaticDegreesSmoothed = newDegreesSmoothed;
                mStaticDegreesIsValid = true;
                {
                    // Pretty lame to do this if there's no activity listening,
                    // but I guess this is the standard way to do it.
                    // CBB: think about whether it makes sense to have a "numBroadcastListeners" variable,
                    // and if it's zero, don't bother to send
                    Intent intent = new Intent("degrees changed");
                    intent.putExtra("old degrees", oldDegrees);
                    intent.putExtra("new degrees", newDegrees);
                    intent.putExtra("old degrees smoothed", oldDegreesSmoothed);
                    intent.putExtra("new degrees smoothed", newDegreesSmoothed);
                    LocalBroadcastManager.getInstance(TheService.this).sendBroadcast(intent);
                }

                if (mVerboseLevel >= 2) Log.i(TAG, "        out onOrientationChanged(degrees="+degrees+")");
            }
        };  // mOrientationEventListener


        // same thing we do on ACTION_SCREEN_ON (dup code)
        if (mOrientationEventListener.canDetectOrientation() == true) {
            if (mVerboseLevel >= 1) Log.i(TAG, "                          can detect orientation, enabling orientation event listener");
            mOrientationEventListener.enable();
        } else {
            if (mVerboseLevel >= 1) Log.i(TAG, "                          cannot detect orientation, disabling orientation event listener");
            mOrientationEventListener.disable();
        }

        {
            // Note that some phones accelerometers work in standby mode (screen off), some don't:
            //     http://www.saltwebsites.com/2012/android-accelerometers-screen-off
            // For the ones that do, we want to stop listening to the accelerometer when the screen is off,
            // to conserve battery.
            // https://thinkandroid.wordpress.com/2010/01/24/handling-screen-off-and-screen-on-intents/
            Log.i(TAG, "android.content.Intent.ACTION_SCREEN_OFF = "+Intent.ACTION_SCREEN_OFF);
            Log.i(TAG, "android.content.Intent.ACTION_SCREEN_ON = "+Intent.ACTION_SCREEN_ON);

            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
            intentFilter.addAction(Intent.ACTION_SCREEN_ON);
            intentFilter.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(mBroadcastReceiver, intentFilter);
        }

        updateOverrideIfNecessary();
        if (mVerboseLevel >= 1) Log.i(TAG, "                        out TheService.onCreate");
    }  // onCreate

    @Override
    public int onStartCommand(Intent startIntent, int flags, int startId) {
        if (mVerboseLevel >= 1) Log.i(TAG, "                            in TheService.onStartCommand(startIntent, flags="+flags+", startId="+startId+")");
        if (mVerboseLevel >= 1) Log.i(TAG, "                              startIntent = "+startIntent);

        if (true)
        {
            // Use startForeground() to "put the service in a foreground state,
            // where the system considers it to be something the user is actively aware of
            // and thus not a candidate for killing when low on memory".
            // https://developer.android.com/guide/components/services.html#Foreground

            // Notes:
            //   - the pendingIntent is so when user clicks on the notification,
            //     it will open the activity.
            //   - this also makes it so that when the application is killed (e.g.
            //     by hitting the stop sign in AS Android Monitor, or reinstalling), the service
            //     will be restarted.
            // Questions:
            //   - how do I keep the icon from going away when user opens the activity by clicking on the notification?
            Intent notificationIntent = new Intent(this, TheActivity.class);
            http://stackoverflow.com/questions/7385443/flag-activity-clear-top-in-android#answer-7385849
            notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
            final Notification.Builder builder = new Notification.Builder(this)
                    .setContentTitle("Adaptive Rotation Lock Shim") // XXX R.string.notification_title
                    .setContentText("Tap for configuration options") // XXX R.string.notification_messsage
                    //.setSmallIcon(R.drawable.typewriter_el)
                    .setSmallIcon(R.mipmap.typewriter_el)
                    .setContentIntent(pendingIntent)
                    //.setOngoing(true) // XXX doesn't seem to help keep the icon up
                    .setWhen(System.currentTimeMillis()+10*60*1000)
                    .setShowWhen(true)
                    ;
            final Notification notification = builder.build();
            //notification.flags |= Notification.FLAG_NO_CLEAR; // XXX doesn't seem to help keep the icon up
            if (mVerboseLevel >= 1) Log.i(TAG, "                              calling startForeground");
            startForeground(AN_IDENTIFIER_FOR_THIS_NOTIFICATION_UNIQUE_WITHIN_THIS_APPLICATION, notification);
            if (mVerboseLevel >= 1) Log.i(TAG, "                              returned from startForeground");
            if (false) {
                if (mVerboseLevel >= 1) Log.i(TAG, "                              calling stopForeground");
                stopForeground(AN_IDENTIFIER_FOR_THIS_NOTIFICATION_UNIQUE_WITHIN_THIS_APPLICATION);
                if (mVerboseLevel >= 1) Log.i(TAG, "                              returned from stopForeground");
            }


            // This was an experiment to see if I can successfully update the notification text.  Yes, it works, pretty smoothly.
            if (false) {
                final int count[] = {0};
                final Handler handler = new Handler();
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (mHasBeenDestroyed) {
                            // stop updating the notification!
                            // TODO: We actually shouldn't have gotten this far; should make onDestroy() cancel us.
                            if (mVerboseLevel >= 1) Log.i(TAG, "service has been destroyed! abandoning notification update");
                            return;
                        }
                        builder.setContentText("updated "+(count[0]++));
                        if (false) {
                            NotificationManager notificationManager = (NotificationManager) getApplicationContext().getSystemService(getApplicationContext().NOTIFICATION_SERVICE);
                            // There seems to be some disagreement over whether the following will
                            // do something unfriendly-- either undo startForeground() (I don't think so)
                            // or make it so stopForeground() no longer removes the notification (yeah I think so).
                            notificationManager.notify(AN_IDENTIFIER_FOR_THIS_NOTIFICATION_UNIQUE_WITHIN_THIS_APPLICATION, builder.build());
                            //stopForeground(AN_IDENTIFIER_FOR_THIS_NOTIFICATION_UNIQUE_WITHIN_THIS_APPLICATION);
                        }
                        if (true) {
                            startForeground(AN_IDENTIFIER_FOR_THIS_NOTIFICATION_UNIQUE_WITHIN_THIS_APPLICATION, notification);
                        }
                        handler.postDelayed(this, 1000);
                    }
                }, 1000);
            }

        }

        showToast(this, "Service Started", 2000);  // this is immediately after the "Service Created" for 1 second... so it will really only show for 1 second due to the bug XXX

        if (mStaticAutoRotate || mStaticWhackAMole) {
            // Make sure we don't fight with the system's ACCELEROMETER_ROTATION.
            // This is part of what happens when user selects Settings -> Display -> "When device is rotated": "Stay in portrait view".
            // (the other thing that happens is that USER_ROTATION gets set to 0 too, if it wasn't 0).
            Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0);
            // Do this here in addition to in listener, since it seems to be best practice
            // (judging from the analogous situation for USER_ROTATION)
            mCurrentSystemSettingACCELEROMETER_ROTATION = 0;
        }

        new Handler().postDelayed(new Runnable() {
            private int i = 0;
            @Override
            public void run() {
                Intent intent = new Intent("service started");
                if (mVerboseLevel == 1) Log.i(TAG, "                              sending \"service started\" broadcast");
                LocalBroadcastManager.getInstance(TheService.this).sendBroadcast(intent);
            }
        }, TINY_DELAY); // tiny delay before telling activity, to test synchronization behavior



        if (mVerboseLevel >= 1) Log.i(TAG, "                            out TheService.onStartCommand(startIntent, flags="+flags+", startId="+startId+")");
        return START_STICKY; // Continue running until explicitly stopped, and restart the app process with service only if it gets kill by e.g. stopsign button in Android Monitor in AS
    }  // onStartCommand

    @Override
    public void onDestroy() {
        if (mVerboseLevel >= 1) Log.i(TAG, "                        in TheService.onDestroy");
        // TODO: cancel previous toast if any
        showToast(this, "Service Destroyed", 2000);

        {
            WindowManager windowManager = (WindowManager)getSystemService(Service.WINDOW_SERVICE);
            windowManager.removeView(mOrientationChanger);
            mOrientationChanger = null;
        }

        getContentResolver().unregisterContentObserver(mAccelerometerRotationObserver);
        mAccelerometerRotationObserver = null;

        getContentResolver().unregisterContentObserver(mUserRotationObserver);
        mUserRotationObserver = null;

        mOrientationEventListener.disable();
        mOrientationEventListener = null;

        unregisterReceiver(mBroadcastReceiver);
        mBroadcastReceiver = null;

        theRunningService = null;
        mHasBeenDestroyed = true;

        // Reasons we might be getting stopped while activity is still running:
        // - user toggled the switch in the activity
        // - something else called stopService
        // - in the latter case, we must tell the activity, otherwise the switch will not be in sync.
        //   XXX examine whether I've adressed that
            
        new Handler().postDelayed(new Runnable() {
            private int i = 0;
            @Override
            public void run() {
                Intent intent = new Intent("service destroyed");
                if (mVerboseLevel == 1) Log.i(TAG, "                              sending \"service destroyed\" broadcast");
                LocalBroadcastManager.getInstance(TheService.this).sendBroadcast(intent);
            }
        }, TINY_DELAY); // tiny delay before telling activity, to test synchronization behavior
        // XXX race? what if user is in the process of turning it on??
        if (mVerboseLevel >= 1) Log.i(TAG, "                        out TheService.onDestroy");
    }  // onDestroy

    // XXX not sure this is the way I want to do things
    public static boolean getRed() {
        return mStaticRed;
    }

    public static void setOverride(boolean newOverride) {
      // TODO: should this method be responsible for noticing it's the same as previous?
      mStaticOverride = newOverride;
      if (theRunningService != null) {
        theRunningService.updateOverrideIfNecessary();
      }
    }

    // TODO: animate the red!   (TODO: what was I talking about?)
    public static void setRed(boolean newRed) {
        Log.i(TAG, "                in setRed");
        Log.i(TAG, "                  theRunningService = "+theRunningService);
        mStaticRed = newRed;
        if (theRunningService != null) {
            final int oldColor = theRunningService.mOrientationChangerCurrentBackgroundColor;
            final int newColor = (mStaticRed ? 0x44ff0000 : 0x00000000); // faint translucent red color if mStaticRed
            theRunningService.mOrientationChangerCurrentBackgroundColor = newColor;
            if (false) {
                Log.i(TAG, "                      instantly setting background color to "+String.format("%#x", newColor));
                theRunningService.mOrientationChanger.setBackgroundColor(theRunningService.mOrientationChangerCurrentBackgroundColor);
            } else {
                // Fade it in
                Log.i(TAG, "                      fading background color to "+String.format("%#x", newColor));
                final long fadeMillis = 500;
                final int n = 20; // too small makes it juddery.  too big makes it take a long time.
                final Handler handler = new Handler();
                Runnable runnable = new Runnable() {
                    private int i = 0;
                    @Override
                    public void run() {
                        i++;
                        double frac = (double)i / (double)n;
                        int midColor = 0;
                        for (int i = 0; i < 4; ++i) {
                            int oldByte = (oldColor >> (i*8)) & 0xff;
                            int newByte = (newColor >> (i*8)) & 0xff;
                            int midByte = (int)((1.-frac)*oldByte
                                                + frac*newByte + .5);
                            midColor |= midByte << (i*8);
                        }
                        theRunningService.mOrientationChanger.setBackgroundColor(midColor);
                        if (i < n) {
                            handler.postDelayed(this, fadeMillis / n);
                        }
                    }
                };
                runnable.run();
            }
        }
        Log.i(TAG, "                out setRed");
    }  // setRed

    private void updateOverrideIfNecessary() {
      if (mVerboseLevel >= 1) Log.i(TAG, "                in updateOverrideIfNecessary");
      // TODO: need to listen to changes on mStaticOverride, and do the following when it changes too
      WindowManager windowManager = (WindowManager)getSystemService(WINDOW_SERVICE); // there's no getWindowManager() in a service
      if (mStaticOverride) {
          int newScreenOrientationConstant;
          if (mStaticClosestCompassPoint != -1) {
            newScreenOrientationConstant = closestCompassPointToScreenOrientationConstant(mStaticClosestCompassPoint);
          } else {
            newScreenOrientationConstant = mOrientationLayout.screenOrientation;  // so that this doesn't cause us to change anything
          }

          if (mVerboseLevel >= 1) Log.i(TAG, "              attempting to force orientation to "+screenOrientationConstantToString(newScreenOrientationConstant));
          if ((mOrientationChanger.getVisibility() != View.VISIBLE)
           || mOrientationLayout.screenOrientation != newScreenOrientationConstant
           || mOrientationChangerCurrentBackgroundColor != (mStaticRed ? 0x44ff0000 : 0x00000000)) {
              mOrientationChangerCurrentBackgroundColor = (mStaticRed ? 0x44ff0000 : 0x00000000); // faint translucent red color if mStaticRed
              mOrientationChanger.setBackgroundColor(mOrientationChangerCurrentBackgroundColor);
              mOrientationLayout.screenOrientation = newScreenOrientationConstant;
              windowManager.updateViewLayout(mOrientationChanger, mOrientationLayout);
              mOrientationChanger.setVisibility(View.VISIBLE);
          } else {
              if (mVerboseLevel >= 1) Log.i(TAG, "                  (no need)");
          }
      } else {
          if (mVerboseLevel >= 1) Log.i(TAG, "              attempting to unforce orientation");
          if (mOrientationChanger.getVisibility() != View.GONE) {
              mOrientationChanger.setVisibility(View.GONE);
              mOrientationLayout.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
              windowManager.updateViewLayout(mOrientationChanger, mOrientationLayout);
          } else {
              if (mVerboseLevel >= 1) Log.i(TAG, "                  (no need)");
          }
      }
      if (mVerboseLevel >= 1) Log.i(TAG, "                out updateOverrideIfNecessary");
    }  // updateOverrideIfNecessary

    // Syncs system USER_ROTATION to mStaticClosestCompassPoint, which must be valid.
    // Also whacks ACCELEROMETER_ROTATION if set (but it shouldn't be).
    private void doTheAutoRotateThingNow() {
        if (mVerboseLevel >= 1) Log.i(TAG, "            in doTheAutoRotateThingNow");
        CHECK(mStaticClosestCompassPoint != -1);

        if (mCurrentSystemSettingACCELEROMETER_ROTATION != 0) {
            // In case this got turned on for some reason.
            // Probably this can't happen unless mStaticWhackAMole is off. 
            if (mVerboseLevel >= 1) Log.i(TAG, "              changing Settings.System.ACCELEROMETER_ROTATION from " + mCurrentSystemSettingACCELEROMETER_ROTATION + " to 0 !!!!!!!!!!");
            try {
                Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0);
                // Do this here in addition to in listener, since it seems to be best practice
                // (judging from the analogous situation for USER_ROTATION)
                mCurrentSystemSettingACCELEROMETER_ROTATION = 0;
            } catch (SecurityException e) {
                // XXX dup code
                if (mVerboseLevel >= 1) Log.i(TAG, "              Oh no, can't set system settings-- were permissions revoked?");
                Toast.makeText(TheService.this, " Oh no, can't set system settings-- were permissions revoked?\nHere, please grant the permission.", Toast.LENGTH_LONG).show();
                Intent grantIntent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                grantIntent.setData(Uri.parse("package:"+getPackageName()));
                if (mVerboseLevel >= 1) Log.i(TAG, "                  grantIntent = "+grantIntent);
                if (mVerboseLevel >= 1) Log.i(TAG, "                  calling startActivity with ACTION_MANAGE_WRITE_SETTINGS");
                startActivity(grantIntent);
                if (mVerboseLevel >= 1) Log.i(TAG, "                  returned from startActivity with ACTION_MANAGE_WRITE_SETTINGS (but still didn't set ACCELEROMETER_ROTATION like we wanted!)");
                // CBB: still didn't write the value; not sure we can do anything better
                // since permission might not have actually been granted.
            }
        } else {
            if (mVerboseLevel >= 1) Log.i(TAG, "              Settings.System.ACCELEROMETER_ROTATION was 0 as expected");
        }

        // From http://stackoverflow.com/questions/14587085/how-can-i-globally-force-screen-orientation-in-android#answer-26895627
        // Requires WRITE_SETTINGS permission.
        // and also now it requires the canWrite dance (at beginning of Activity).
        // It's possible we don't have permissions now, even though we checked on Activity start.
        // e.g. the user may have never granted them, or granted them and then revoked them later.
        // So, we have to protect these calls.

        // Per https://developer.android.com/reference/android/provider/Settings.System.html:
        // USER_ROTATION: "Default screen rotation when no other policy applies.  When ACCELEROMETER_ROTATION is zero
        // and no on-screen Activity expresses a preference, this rotation value will be used."
        // ACCELEROMETER_ROTATION: "Control whether the accelerometer will be used to change screen orientation.
        // If 0, it will not be used unless explicitly requested by the application;
        // if 1, it will be used by default unless explicitly disabled by the application."
        // (where "explicitly disabled by the application" presumably means an application has called something like
        // setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT))
        // So the players are:
        //      - accelerometer's idea of the device's physical orientation
        //      - ACCELEROMETER_ROTATION (in settings): whether system should keep screen rotation in sync with device's physical orientation
        //      - USER_ROTATION setting (default screen rotation when no other policy applies,
        //          i.e. when ACCELEROMETER_ROTATION is zero and no on-screen Activity expresses a preference)
        // So the following is the algorithm for the screen rotation:
        //      1. if an on-screen Activity expressed a preference
        //              by setRequestedOrientation I think?
        //              Q: what if more than one does?
        //              PA: maybe it uses the "topmost" one in some sense?
        //      2. else if system setting ACCELEROMETER_ROTATION is 1, keep screen orientation in sync with device's physical orientation
        //      3. else use system setting USER_ROTATION
        // XXX what is android:configChanges="orientation|keyboardHidden" and the onConfigurationChanged event?

        int oldUSER_ROTATION = mCurrentSystemSettingUSER_ROTATION;
        int newUSER_ROTATION = closestCompassPointToUserRotation(mStaticClosestCompassPoint);
        CHECK(newUSER_ROTATION != -1); // logical assertion

        // Note, we definitely need to set USER_ROTATION even if overriding.
        // The reason is that if USER_ROTATION is directly opposite what mOrientationLayout specifies,
        // the rotation will get set to USER_ROTATION!  (bug?)
        try {
            if (mVerboseLevel >= 1) Log.i(TAG, "              changing Settings.System.USER_ROTATION from " + surfaceRotationConstantToString(oldUSER_ROTATION) + " to " + surfaceRotationConstantToString(newUSER_ROTATION));
            Settings.System.putInt(getContentResolver(), Settings.System.USER_ROTATION, newUSER_ROTATION);
            // Do this here in addition to in listener, to avoid bug described at the listener!
            mCurrentSystemSettingUSER_ROTATION = newUSER_ROTATION;
        } catch (SecurityException e) {
            if (mVerboseLevel >= 1) Log.i(TAG, "              Oh no, can't set system settings-- were permissions revoked?");
            Toast.makeText(TheService.this, " Oh no, can't set system settings-- were permissions revoked?\nHere, please grant the permission.", Toast.LENGTH_LONG).show();
            Intent grantIntent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
            grantIntent.setData(Uri.parse("package:"+getPackageName()));
            if (mVerboseLevel >= 1) Log.i(TAG, "              grantIntent = "+grantIntent);
            if (mVerboseLevel >= 1) Log.i(TAG, "              calling startActivity with ACTION_MANAGE_WRITE_SETTINGS");
            startActivity(grantIntent);
            if (mVerboseLevel >= 1) Log.i(TAG, "              returned from startActivity with ACTION_MANAGE_WRITE_SETTINGS (but still didn't set USER_ROTATION like we wanted!)");
            // CBB: still didn't write the value.
            // What to do now?
            //     - try to write the value again? (endless loop possible)
            //     - try to write the value again but only try again once?
            //     - try to write the value again but only if it looks like permission was granted?
            //     - just leave it; it will get resolved eventually (if permission eventually granted)
        }

        updateOverrideIfNecessary();

        if (mVerboseLevel >= 1) Log.i(TAG, "            out doTheAutoRotateThingNow");
    }  // doTheAutoRotateThingNow
}
