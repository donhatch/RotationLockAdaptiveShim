package com.example.donhatch.rotationlockadaptiveshim;

// Info on how to force screen rotation for apps that request otherwise,
// using a transparent overlay:
//   http://stackoverflow.com/questions/14587085/how-can-i-globally-force-screen-orientation-in-android/14654302#answer-14654302
//   http://stackoverflow.com/questions/14587085/how-can-i-globally-force-screen-orientation-in-android/14654302#answer-14862852
//   http://stackoverflow.com/questions/10364815/how-does-rotation-locker-work
//   http://stackoverflow.com/questions/32241079/setting-orientation-globally-using-android-service
// And an app with source code that does it:  PerApp

import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
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
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.hardware.SensorEventListener;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

@SuppressWarnings({"ConstantIfStatement", "PointlessArithmeticExpression", "ConstantConditions"})
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

  private static final Object theRunningServiceLock = new Object();
  // Formerly, I had `private static TheService theRunningService = null`,
  // but I got "Warning: Do not place Android context classes in static fields; this is a memory leak (and also breaks Instant Run)".
  // (in Analyze -> Inspect Code... -> Android Line: Performaice -> Static Field Leaks).
  // (Weird that it complains because a TheService object has a mOrientationChanger field which is a LinearLayout,
  // but not that TheService is itself a context)
  // So instead, hold a weak reference and a boolean saying whether we've set it or not.
  // It should *never* get unset from under us, since we set and unset it in onCreate() and onDestroy().
  private static boolean theRunningServiceIsSet = false;  // all accesses must be protected by theRunningServiceLock
  private static java.lang.ref.WeakReference<TheService> theRunningServiceWeakRef = null; // all accesses must be protected by theRunningServiceLock

  public boolean mHasBeenDestroyed = false;
  // omfg it has to be nonzero
  private final int AN_IDENTIFIER_FOR_THIS_NOTIFICATION_UNIQUE_WITHIN_THIS_APPLICATION = 1;

  final int mVerboseLevel = 1; // 0: nothing, 1: major stuff, 2: every accelerometer event (lots).  Only final because the linter suggested it; feel free to make non-final if it helps debug.

  // These are static to avoid having to think about when the service isn't running.
  public static int mStaticDegrees = -1; // most recent value passed to onOrientationChanged listener of any TheService instance.
  public static double mStaticDegreesSmoothed = -1.; // most recent value passed to onOrientationChanged listener of any TheService instance.
  public static boolean mStaticDegreesIsValid = false;
  public static int mStaticClosestCompassPoint = -1; // means invalid
  private OrientationEventListener mOrientationEventListener;
  private SensorManager mSensorManager;
  private SensorEventListener mSensorEventListener;
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
        mSensorManager.unregisterListener(mSensorEventListener);
      } else if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
        // same thing we do on create (dup code)
        if (mOrientationEventListener.canDetectOrientation()) {
          if (mVerboseLevel >= 1) Log.i(TAG, "          ACTION_SCREEN_ON and can detect orientation, enabling orientation event listener");
          mOrientationEventListener.enable();
          mSensorManager.registerListener(mSensorEventListener, mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_NORMAL);
        } else {
          if (mVerboseLevel >= 1) Log.i(TAG, "          ACTION_SCREEN_ON but cannot detect orientation, disabling orientation event listener");
          mOrientationEventListener.disable();
          mSensorManager.unregisterListener(mSensorEventListener);
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
    }
  };  // mBroadcastReceiver


  public static boolean mStaticWhackAMole = true; // TODO: make this a shared preference? the activity is the one who sets this
  public static boolean mStaticAutoRotate = true; // TODO: make this a shared preference? the activity is the one who sets this
  public static boolean mStaticRotateOnShake = false; // TODO: make this a shared preference? the activity is the one who sets this
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

  public static String sensorStatusAccuracyConstantToString(int sensorStatusAccuracyConstant) {
    switch (sensorStatusAccuracyConstant) {
      case SensorManager.SENSOR_STATUS_ACCURACY_LOW: return "SENSOR_STATUS_ACCURACY_LOW";
      case SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM: return "SENSOR_STATUS_ACCURACY_MEDIUM";
      case SensorManager.SENSOR_STATUS_ACCURACY_HIGH: return "SENSOR_STATUS_ACCURACY_HIGH";
      default: return "[unknown sensor status accuracy constant "+sensorStatusAccuracyConstant+"]";
    }
  }  // sensorStatusAccuracyConstantToString

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
    if (mVerboseLevel >= 1) Log.i(TAG, "                          Build.VERSION_CODES.O_MR1 = "+Build.VERSION_CODES.O_MR1);
    if (mVerboseLevel >= 1) Log.i(TAG, "                          getApplicationContext().getApplicationInfo().targetSdkVersion = "+getApplicationContext().getApplicationInfo().targetSdkVersion);
    // note that in real apps installed from play store, I think it's always true that runtime version <= targetSdkVersion

    synchronized(theRunningServiceLock) {
      if (theRunningServiceIsSet) {
        CHECK(theRunningServiceWeakRef != null);
        CHECK(theRunningServiceWeakRef.get() != null);
        // XXX do this via a Notification, I think
        // XXX have I decided this never happens?  Not sure.
        throw new AssertionError("TheService.onCreate called when there's already an existing service!");
      }
      CHECK(!theRunningServiceIsSet);
      CHECK(theRunningServiceWeakRef == null);
      theRunningServiceIsSet = true;
      theRunningServiceWeakRef = new java.lang.ref.WeakReference<TheService>(this);
    }

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
    // but it works (on *runtime* level>=26, anyway, not on runtime 25) even if targetSdkLevel=25.  (So, Q: what will I lose if I say targetSdkLevel=25 instead of 26? only diff I've observed is it allows TYPE_SYSTEM_OVERLAY, which is a *good* thing I think? OH I know, it means playstore won't offer it on runtimes >=26.  so, yeah, stick with 26... I think?  (Q: can I play games with maxSdkLevel to get around that?))
    // Tested:
    //   - TYPE_SYSTEM_OVERLAY with targetSdkLevel=25, runtime 25
    //      - works (not sure if play store keyboard works since play store not on emulator)
    //   - TYPE_SYSTEM_OVERLAY with targetSdkLevel=26,27, runtime 25
    //      - works (not sure if play store keyboard works since play store not on emulator)
    //   - TYPE_APPLICATION_OVERLAY with targetSdkLevel=25, runtime 25
    //      - crashes: android.view.WindowManager$BadTokenException: Unable to add window android.view.ViewRootImpl$W@8e5af08 -- permission denied for window type 2038  # 2038 is TYPE_APPLICATION_OVERLAY
    //   - TYPE_APPLICATION_OVERLAY with targetSdkLevel=26,27, runtime 25
    //      - crashes: android.view.WindowManager$BadTokenException: Unable to add window android.view.ViewRootImpl$W@8e5af08 -- permission denied for window type 2038  # 2038 is TYPE_APPLICATION_OVERLAY

    //   - TYPE_SYSTEM_OVERLAY with targetSdkLevel=25, runtime 26
    //      - works
    //   - TYPE_SYSTEM_OVERLAY with targetSdkLevel=26,27, runtime 26
    //      - crashes: android.view.WindowManager$BadTokenException: Unable to add window android.view.ViewRootImpl$W@f27b55e -- permission denied for window type 2006  # 2006 is TYPE_SYSTEM_OVERLAY
    //   - TYPE_APPLICATION_OVERLAY with targetSdkLevel=25, runtime 26
    //      - works
    //   - TYPE_APPLICATION_OVERLAY with targetSdkLevel=26,27, runtime 26
    //      - works
    // CONCLUSION:  Since I want to keep targetSdkLevel up to date (I think), I need to make a runtime test:
    //      - if runtime<=25, *must* use TYPE_SYSTEM_OVERLAY
    //      - if both targetSdkLevel>=26 and runtime>=26, *must* use TYPE_APPLICATION_OVERLAY
    //      - otherwise (targetSdkLevel<=25, runtime>=26), either will work   (but, weaning myself away from targetSdkLevel=25, so use TYPE_APPLICATION_OVERLAY... also this is impossible if downloaded from play store which enforces runtime<=targetSdkLevel... unless can play games with maxSdkLevel)
    int type;
    int flags;
    boolean use_TYPE_APPLICATION_OVERLAY = Build.VERSION.SDK_INT >= 26; // what's on current machine
    if (false) {  // set to true to override, so can experiment to fill out matrix above
      use_TYPE_APPLICATION_OVERLAY = true;
    }
    final boolean final_use_TYPE_APPLICATION_OVERLAY = use_TYPE_APPLICATION_OVERLAY;
    if (!use_TYPE_APPLICATION_OVERLAY) {
      if (mVerboseLevel >= 1) Log.i(TAG, "                          using TYPE_SYSTEM_OVERLAY");
      type = WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
      flags = 0;
      flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; // not sure whether this is needed for playstore's keyboard (there's no playstore on emulator) but it doesn't hurt
    } else {
      // flow analysis apparently isn't smart enough to see that this block only happens when Build.VERSION.SDK_INT >= 26, wtf?  So help it.  According to the warning doc, TargetApi(11) means pretend minSdkLevel=11.
      if (mVerboseLevel >= 1) Log.i(TAG, "                          using TYPE_APPLICATION_OVERLAY with FLAG_NOT_TOUCHABLE|FLAG_NOT_FOCUSABLE");
      type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY; // works for both now that I figured out the FLAG_NOT_TOUCHABLE thing
      flags = 0;
      flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; // required in order for touchthrough to work at all
      flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; // required in order for playstore's keyboard to function when under this overlay (wtf!?)
      //flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL // irrelevant since FLAG_NOT_FOCUSABLE is set
    }

    mOrientationLayout = new WindowManager.LayoutParams(
      /*_type=*/type,
      /*_flags=*/flags,
      /*_format=*/PixelFormat.RGBA_8888);
    CHECK(mOrientationLayout.screenOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    {
      WindowManager windowManager = (WindowManager)getSystemService(Service.WINDOW_SERVICE);
      if (mVerboseLevel >= 1) Log.i(TAG, "                          adding view mOrientationchanger, cross your fingers");
      windowManager.addView(mOrientationChanger, mOrientationLayout);  // THIS is where it crashes if using TYPE_APPLICATION_OVERLAY and targetSdkLevel and/or runtime is <=25
      if (mVerboseLevel >= 1) Log.i(TAG, "                          added view mOrientationchanger; it worked!");
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
          boolean closestCompassPointChanging;
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

                    if (mVerboseLevel == 1) Log.i(TAG, "          attempting to pop up a Dialog");

                    final Context c = getApplicationContext();
                    final int threeOrSomething = 3;
                    final CheckBox dontAskAgainCheckBox = new CheckBox(c) {{
                      setText("Don't ask again");
                      setPadding(10,10, 10,10);  // touchable
                    }};
                    final CheckBox andLockCheckBox = new CheckBox(c) {{
                      setText("and lock");
                      setPadding(10,10, 10,10);  // touchable
                    }};
                    final TextView messageTextView = new TextView(c) {{
                      setText(threeOrSomething+"...");
                    }};
                    final TextView NOtextView = new TextView(c) {{
                      setText("NO");
                      setTextColor(0xff0000ff);  // blue
                      setPadding(10,10, 10,10);  // touchable
                      setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                          if (mVerboseLevel == 1) Log.i(TAG, "            in alertDialog negative button onClick");

                          // DUP CODE ALERT
                          if (andLockCheckBox.isChecked()) {
                            mStaticAutoRotate = false;
                            Intent intent = new Intent("mStaticAutoRotate changed");
                            intent.putExtra("new mStaticAutoRotate", mStaticAutoRotate);
                            if (mVerboseLevel == 1) Log.i(TAG, "              sending \"mStaticAutoRotate changed\" broadcast");
                            LocalBroadcastManager.getInstance(TheService.this).sendBroadcast(intent);
                            if (mVerboseLevel == 1) Log.i(TAG, "              sent \"mStaticAutoRotate changed\" broadcast");
                          } else if (dontAskAgainCheckBox.isChecked()) {
                            mStaticAutoRotate = false;
                            Intent intent = new Intent("mStaticAutoRotate changed");
                            intent.putExtra("new mStaticAutoRotate", mStaticAutoRotate);
                            if (mVerboseLevel == 1) Log.i(TAG, "              sending \"mStaticAutoRotate changed\" broadcast");
                            LocalBroadcastManager.getInstance(TheService.this).sendBroadcast(intent);
                            if (mVerboseLevel == 1) Log.i(TAG, "              sent \"mStaticAutoRotate changed\" broadcast");
                          }
                          // XXX is this evidence for why it's good to always delay?
                          if (mCleanupDialog != null) {
                            mCleanupDialog.run();
                            mCleanupDialog = null;
                          }
                          // END DUP CODE ALERT

                          if (mVerboseLevel == 1) Log.i(TAG, "            out alertDialog negative button onClick");
                        }
                      });
                    }};
                    final TextView YEStextView = new TextView(c) {{
                      setText("YES");
                      setTextColor(0xff0000ff);  // blue
                      setPadding(10,10, 10,10);  // touchable
                      setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                          if (mVerboseLevel == 1) Log.i(TAG, "            in alertDialog positive button onClick");

                          // DUP CODE ALERT
                          if (andLockCheckBox.isChecked()) {
                            mStaticAutoRotate = false;
                            Intent intent = new Intent("mStaticAutoRotate changed");
                            intent.putExtra("new mStaticAutoRotate", mStaticAutoRotate);
                            if (mVerboseLevel == 1) Log.i(TAG, "              sending \"mStaticAutoRotate changed\" broadcast");
                            LocalBroadcastManager.getInstance(TheService.this).sendBroadcast(intent);
                            if (mVerboseLevel == 1) Log.i(TAG, "              sent \"mStaticAutoRotate changed\" broadcast");
                          } else if (dontAskAgainCheckBox.isChecked()) {
                            mStaticPromptFirst = false;
                            Intent intent = new Intent("mStaticPromptFirst changed");
                            intent.putExtra("new mStaticPromptFirst", mStaticPromptFirst);
                            if (mVerboseLevel == 1) Log.i(TAG, "              sending \"mStaticPromptFirst changed\" broadcast");
                            LocalBroadcastManager.getInstance(TheService.this).sendBroadcast(intent);
                            if (mVerboseLevel == 1) Log.i(TAG, "              sent \"mStaticPromptFirst changed\" broadcast");
                          }
                          // XXX is this evidence for why it's good to always delay?
                          if (mCleanupDialog != null) {
                            mCleanupDialog.run();
                            mCleanupDialog = null;
                          }
                          // END DUP CODE ALERT

                          doTheAutoRotateThingNow();
                          if (mVerboseLevel == 1) Log.i(TAG, "            out alertDialog positive button onClick");
                        }
                      });
                    }};

                    final Handler[] handlerHolder = new Handler[1];
                    final Runnable[] runnableHolder = new Runnable[1];

                    // What I want is basically an AlertDialog with setView("Don't ask again" checkbox),
                    // but I want it much tighter packed.
                    final Dialog dialog = new Dialog(c) {
                      @Override
                      public boolean onTouchEvent(@NonNull MotionEvent motionEvent) {
                        if (mVerboseLevel == 1) Log.i(TAG, "            in alertDialog onTouchEvent");
                        if (mVerboseLevel == 1) Log.i(TAG, "              motionEvent.getActionMasked()="+motionEventActionMaskedConstantToString(motionEvent.getActionMasked()));
                        if (motionEvent.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                          if (mVerboseLevel == 1) Log.i(TAG, "              touch outside dialog! cancelling and letting it pass through");
                          if (mCleanupDialog != null) {
                            mCleanupDialog.run();
                            mCleanupDialog = null;
                          }
                        } else {
                          // Treat
                          if (mVerboseLevel == 1) Log.i(TAG, "            touch inside dialog: cancelling expiration");
                          handlerHolder[0].removeCallbacks(runnableHolder[0]);
                          if (mVerboseLevel == 1) Log.i(TAG, "            touch inside dialog: cancelled expiration");
                          if (mVerboseLevel == 1) Log.i(TAG, "          out alertDialog onTouchEvent");
                          return true;
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
                    dialog.setTitle("Rotate the screen?");
                    dialog.setContentView(new LinearLayout(c) {{
                      setOrientation(VERTICAL);
                      addView(new LinearLayout(c) {{
                        // Some horizontal space before messageTextView.  Not very principled,
                        // but better than hard-coding it into the message every time we set the message.
                        addView(new TextView(c) {{
                          setText("   ");
                        }});
                        addView(messageTextView);
                      }});
                      addView(new LinearLayout(c) {{
                        setOrientation(HORIZONTAL);
                        if (false) {  // not sure how to logically integrate this with the other controls, so leaving it out for now
                          addView(dontAskAgainCheckBox);
                          addView(new TextView(c),
                                  new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT) {{
                                    weight = 1.f;  // stretch
                                  }});
                          addView(new TextView(c) {{ setText("       "); }});  // line up with title
                        }
                        addView(NOtextView);
                        addView(new TextView(c),
                                new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT) {{
                                  weight = 1.f;  // stretch
                                }});
                        addView(YEStextView);
                        addView(new TextView(c),
                                new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT) {{
                                  weight = 1.f;  // stretch
                                }});
                        addView(andLockCheckBox);
                        addView(new TextView(c) {{ setText("       "); }});
                      }});
                    }});

                    Window alertDialogWindow = dialog.getWindow();

                    // This setType is required when context=TheService.this,
                    // otherwise the show() gives WindowManager.BadTokenException.
                    // prior to targetSdkVersion=26, I used TYPE_SYSTEM_ALERT, but that doesn't work any more
                    // (if both targetSdkVersion>=26 and runtime>=26);
                    // the new thing is TYPE_APPLICATION_OVERLAY instead.
                    // (I assume the same decision matrix holds for this as for the overlay, above).
                    if (!final_use_TYPE_APPLICATION_OVERLAY) {
                      alertDialogWindow.setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
                    } else {
                      alertDialogWindow.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                    }

                    // Kill the dimming of the activity or whatever's behind it
                    alertDialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                    // Kill the drop shadow too
                    // (woops, I had this commented out, but when I tried uncommenting it,
                    // it didn't work-- it made the whole dialog's background transparent!)
                    // (I don't care; the drop shadow is fine)
                    //alertDialogWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));


                    // Trying advice in http://iserveandroid.blogspot.com/2011/04/how-to-dismiss-your-non-modal-dialog.html ...
                    // Make it so user can interact with rest of screen
                    alertDialogWindow.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
                    // And so that we get the onTouchEvent first, when a touch comes on rest of screen
                    alertDialogWindow.addFlags(WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);


                    try {
                      dialog.show();
                    } catch (WindowManager.BadTokenException e) {
                      // This happens if I omit android.permission.SYSTEM_ALERT_WINDOW from AndroidManifest, or if user revoke or didn't grant "Draw over other apps".
                      // TODO: need to do the double-opt-in dance here, similar to the WRITE_SETTINGS permission
                      // TODO: also fails if I try to pop up the dialog, on targetSdkLevel>=26.  ah, it's because TYPE_SYSTEM_ALERT is deprecated for non-system apps, use TYPE_APPLICATION_OVERLAY instead?
                      CHECK(false);
                    }
                    // Make it expire in 3 (or something) seconds.
                    final Handler handler = new Handler();
                    final Runnable runnable = new Runnable() {
                      @Override
                      public void run() {
                        if (mVerboseLevel == 1) Log.i(TAG, "            in run: prompt expired; canceling alert dialog");
                        dialog.cancel();
                        mCleanupDialog = null;
                        if (mVerboseLevel == 1) Log.i(TAG, "            out run: prompt expired; cancelled alert dialog");
                      }
                    };
                    handler.postDelayed(runnable, threeOrSomething*1000);

                    // For the dialog method that needs to be able to cancel it...
                    handlerHolder[0] = handler;
                    runnableHolder[0] = runnable;

                    {
                      CheckBox.OnCheckedChangeListener userIsMessingWithDialogListener = new CheckBox.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                          // User is messing with the dialog.
                          // Kill the countdown, so that the dialog won't disappear til ey hit Yes or No.
                          // CBB: dialog still disappears when user rotates back to previous orientation-- it shouldn't!
                          // Or maybe it should-- think about it.
                          if (mVerboseLevel == 1) Log.i(TAG, "            in onCheckedChanged: canceling expiration");
                          handler.removeCallbacks(runnable);
                          if (mVerboseLevel == 1) Log.i(TAG, "            out onCheckedChanged: cancelled expiration");
                        }
                      };
                      dontAskAgainCheckBox.setOnCheckedChangeListener(userIsMessingWithDialogListener);
                      andLockCheckBox.setOnCheckedChangeListener(userIsMessingWithDialogListener);
                    }

                    CHECK(mCleanupDialog == null);
                    mCleanupDialog = new Runnable() {
                      @Override
                      public void run() {
                        if (mVerboseLevel == 1) Log.i(TAG, "            cleaning up previous dialog");
                        dialog.cancel();
                        handler.removeCallbacks(runnable); // ok if it wasn't scheduled
                        if (mVerboseLevel == 1) Log.i(TAG, "            cleaned up previous dialog");
                      }
                    };

                    // Hacky countdown in the dialog.
                    // These don't get cleaned up by mCleanupDialog, but it doesn't matter; they don't hurt anything.
                    // E.g. if threeOrSomething is 3, then:
                    //   "2..." 1 second from now,
                    //   "1..." 2 seconds from now.
                    for (int i = threeOrSomething-1; i >= 1; --i) {
                      final int iFinal = i;
                      new Handler().postDelayed(new Runnable() { @Override public void run() { messageTextView.setText(iFinal+"..."); } }, (threeOrSomething-iFinal)*1000);
                    }
                    // And a final one to clear the message, in case the callback that removes the dialog gets removed.
                    handler.postDelayed(new Runnable() { @Override public void run() { messageTextView.setText(""); } }, threeOrSomething*1000);

                    if (mVerboseLevel == 1) Log.i(TAG, "          attempted to pop up a Dialog");
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


    // And shake detection needs more direct access to a (the?) sensor manager...
    // https://stackoverflow.com/questions/2317428/android-i-want-to-shake-it#answer-2318356
    // Wait, what?  That math seems screwy.
    mSensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
    CHECK(mSensorManager == (SensorManager)getSystemService(Context.SENSOR_SERVICE)); // we get the same one each time
    mSensorEventListener = new SensorEventListener() {
      private boolean previousIsValid = false; // XXX TODO: need to reset this on reset
      private float xPrev = 0.f;
      private float yPrev = 0.f;
      private float zPrev = 0.f;
      private float dxFiltered = 0.f;
      private float dyFiltered = 0.f;
      private float dzFiltered = 0.f;

      @Override
      public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (mVerboseLevel >= 0) Log.i(TAG, "        in onAccuracyChanged(accuracy="+accuracy+")");
        if (false) {
          // Empirically, we get an immediate MEDIUM.  Whatever.
          showToast(TheService.this, "accelerometer accuracy changed to "+sensorStatusAccuracyConstantToString(accuracy)+", not sure what that means", 2000);
        }
        if (mVerboseLevel >= 0) Log.i(TAG, "        out onAccuracyChanged(accuracy="+accuracy+")");
      }
      @Override
      public void onSensorChanged(SensorEvent sensorEvent) {
        if (mVerboseLevel >= 2) Log.i(TAG, "        in onSensorChanged()");

        float x = sensorEvent.values[0];
        float y = sensorEvent.values[1];
        float z = sensorEvent.values[2];
        float mag = (float)Math.sqrt(x*x + y*y + z*z);

        if (mVerboseLevel >= 2) Log.i(TAG, "          value = "+x+", "+y+", "+z);
        // WORK IN PROGRESS.  Not sure what to consider a shake, at the moment.
        if (false) {
          if (previousIsValid) {
          float dx = x - xPrev;
          float dy = y - yPrev;
          float dz = z - zPrev;
          if (mVerboseLevel >= 2) Log.i(TAG, "          delta = "+dx+", "+dy+", "+dz);
          // perform low-cut filter (sort of).  Not sure what value this has.
          //float memory = 0.9f;
          float memory = 0.0f;
          dxFiltered = dxFiltered * memory + dx;
          dyFiltered = dyFiltered * memory + dy;
          dzFiltered = dzFiltered * memory + dz;

          float shakeFiltered = (float)Math.sqrt(dxFiltered*dxFiltered + dyFiltered*dyFiltered + dzFiltered*dzFiltered);

          if (false) {
            float threshold = 2.f;
            if (shakeFiltered >= threshold) {
            showToast(TheService.this, "Shake: strength "+shakeFiltered, 500);
            }
          }
          }
        }

        if (mStaticRotateOnShake) {
          // Possible strategy:
          // Super strong in +-x or +-y is interpreted as a shakedown.  (Shakeups are possible but difficult, so don't worry about them).
          //float threshold = SensorManager.GRAVITY_EARTH * 2.0f;
          //float threshold = SensorManager.GRAVITY_EARTH * 1.5f;
          float threshold = SensorManager.GRAVITY_EARTH * 1.25f;
          if (mag > threshold) {
            int majorAxis = -1;
            for (int i = 0; i < 3; ++i) {
            if (majorAxis == -1 || Math.abs(sensorEvent.values[i]) > Math.abs(sensorEvent.values[majorAxis])) {
              majorAxis = i;
            }
            }
            float majorMag = Math.abs(sensorEvent.values[majorAxis]);
            double minorMag = Math.hypot(sensorEvent.values[(majorAxis+1)%3], sensorEvent.values[(majorAxis+2)%3]);
            double angleDegrees = Math.atan2(minorMag, majorMag)*180/Math.PI;;
            //showToast(TheService.this, "Shakedown: mag "+mag+"\n\nmajorAxis="+majorAxis+"\n\ndegrees "+angleDegrees+"\n\n"+x+" "+y+" "+z, 5000);
            if (angleDegrees < 22.5) {
            //showToast(TheService.this, "Doing it!", 5000);
            doTheAutoRotateThingNow();
            }
          }
        }


        xPrev = x;
        yPrev = y;
        zPrev = z;
        previousIsValid = true;

        if (mVerboseLevel >= 2) Log.i(TAG, "        out onSensorChanged()");
      }
    };
    mSensorManager.registerListener(mSensorEventListener, mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_NORMAL);


    // same thing we do on ACTION_SCREEN_ON (dup code)
    if (mOrientationEventListener.canDetectOrientation()) {
      if (mVerboseLevel >= 1) Log.i(TAG, "                          can detect orientation, enabling orientation event listener");
      mOrientationEventListener.enable();
      mSensorManager.registerListener(mSensorEventListener, mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_NORMAL);
    } else {
      if (mVerboseLevel >= 1) Log.i(TAG, "                          cannot detect orientation, disabling orientation event listener");
      mOrientationEventListener.disable();
      mSensorManager.unregisterListener(mSensorEventListener);
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

    if (true)  // XXX this stopped working after I upgraded to android 8.1.0 ??? have to set it to false  (well then why is it still true?  need to revisit this)
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
      // http://stackoverflow.com/questions/7385443/flag-activity-clear-top-in-android#answer-7385849
      notificationIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
      PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);



      String NOTIFICATION_CHANNEL_ID = "hello, I am a notification channel id"; // XXX ?

      if (Build.VERSION.SDK_INT >= 26) {  // runtime when channel API introduced
        if (mVerboseLevel >= 1) Log.i(TAG, "                              doing the notification channel setup thing because runtime is "+Build.VERSION.SDK_INT+" >= 26");
        // https://stackoverflow.com/questions/45711925/failed-to-post-notification-on-channel-null-target-api-is-26#answer-47135605
        // Set up a notification channel.
        // This is needed to avoid this warning toast on runtime 26:
        //        Developer warning for package "com.example.donhatch.rotationlockadaptiveshim"
        //        Failed to post notification on channel "null"
        //        See log for more details
        // and this crash on runtime 27:
        //        android.app.RemoteServiceException: Bad notification for startForeground: java.lang.RuntimeException: invalid channel for service notification: Notification(channel=null pri=0 contentView=null vibrate=null sound=null defaults=0x0 flags=0x40 color=0x00000000 vis=PRIVATE)
        int importance = NotificationManager.IMPORTANCE_LOW;
        CharSequence channelName = "hello, I am a notification channel name";  // XXX yow, this comes up as a "category"... furthermore once I create one, it stays on the machine until I uninstall and reinstall the app.
        NotificationChannel notificationChannel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, importance);

        if (false) {
        // Note, the following is all from the example, I haven't looked into whether they have merit or whether there are more appropriate values.
        //notificationChannel.enableLights(true);
        //notificationChannel.setLightColor(Color.RED);
        //notificationChannel.enableVibration(true);
        //notificationChannel.setVibrationPattern(new long[]{100, 200, 300, 400, 500, 400, 300, 200, 400});
        }

        // XXX why did the other one do it through getApplicationContext()? I'm confused
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.createNotificationChannel(notificationChannel);
      }

      // Use NotificationCompat.Builder instead of Notification.Builder;
      // that saves us from having to do a bunch of version checks about channel id (introduced in 26)
      // and build() (replaced getNotification() in 16).
      // and setShowWhen (introduced in 17).
      final NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
          .setContentTitle("Adaptive Rotation Lock Shim") // XXX R.string.notification_title
          .setContentText("Tap for configuration options") // XXX R.string.notification_messsage
          //.setSmallIcon(R.drawable.typewriter_el)
          .setSmallIcon(R.mipmap.typewriter_el)
          .setContentIntent(pendingIntent)
          //.setOngoing(false) // irrelevant: apparently sets FLAG_ONGOING_EVENT (in accordance to documented behavior) but not FLAG_NO_CLEAR, despite documented behavior); however, neither has any affect since we're using startForeground which automatically makes the notification ongoing and non-clearable no matter what these flags say..
          .setWhen(System.currentTimeMillis()+20*60*1000)   // causes "in 9m" or something to be shown if setShowWhen is true.  60 seconds or less turns into "now". only gets updated very infrequently (once a minute?) so not all that useful.
          .setShowWhen(false) // default changed from true to false in (target=)Nougat, so say it explicitly
          ;
      final Notification notification = builder.build();

      if (mVerboseLevel >= 1) Log.i(TAG, "                              calling startForeground");
      startForeground(AN_IDENTIFIER_FOR_THIS_NOTIFICATION_UNIQUE_WITHIN_THIS_APPLICATION, notification);
      if (mVerboseLevel >= 1) Log.i(TAG, "                              returned from startForeground");
      if (false) {
        if (mVerboseLevel >= 1) Log.i(TAG, "                              calling stopForeground");
        stopForeground(true); // use the boolean version, not the int one, for more backwards compatibility
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
              NotificationManager notificationManager = (NotificationManager) getApplicationContext().getSystemService(android.content.Context.NOTIFICATION_SERVICE);
              // There seems to be some disagreement over whether the following will
              // do something unfriendly-- either undo startForeground() (I don't think so)
              // or make it so stopForeground() no longer removes the notification (yeah I think so).
              notificationManager.notify(AN_IDENTIFIER_FOR_THIS_NOTIFICATION_UNIQUE_WITHIN_THIS_APPLICATION, notification);
              //stopForeground(true); // use the boolean version, not the int one, for more backwards compatibility
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

    mSensorManager.unregisterListener(mSensorEventListener);
    mSensorEventListener = null;

    unregisterReceiver(mBroadcastReceiver);
    mBroadcastReceiver = null;

    synchronized(theRunningServiceLock) {
      // Note, these CHECKs are relatively new; not sure they hold
      CHECK(theRunningServiceIsSet);
      CHECK(theRunningServiceWeakRef != null);
      CHECK(theRunningServiceWeakRef.get() != null);
      theRunningServiceIsSet = false;
      theRunningServiceWeakRef = null;
    }
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

  public static boolean isServiceRunning() {
    synchronized(theRunningServiceLock) {
      if (theRunningServiceIsSet) {
      CHECK(theRunningServiceWeakRef != null);
      CHECK(theRunningServiceWeakRef.get() != null);
      } else {
      CHECK(theRunningServiceWeakRef == null);
      }
      return theRunningServiceIsSet;
    }
  }

  // XXX not sure this is the way I want to do things
  public static boolean getRed() {
    return mStaticRed;
  }

  // Set whether to override apps' preferences.  Can be called by the activity
  // (but should do it more legitimately via a message or something).
  public static void setOverride(boolean newOverride) {
    // TODO: should this method be responsible for noticing it's the same as previous?
    mStaticOverride = newOverride;
    synchronized(theRunningServiceLock) {
    if (theRunningServiceIsSet) {
      CHECK(theRunningServiceWeakRef != null);
      CHECK(theRunningServiceWeakRef.get() != null);
      theRunningServiceWeakRef.get().updateOverrideIfNecessary();
    }
    }
  }

  // TODO: animate the red!   (TODO: what was I talking about?)
  public static void setRed(boolean newRed) {
    Log.i(TAG, "                in setRed");
    mStaticRed = newRed;
    synchronized(theRunningServiceLock) {
      Log.i(TAG, "                  theRunningServiceIsSet = "+theRunningServiceIsSet);
      if (theRunningServiceIsSet) {
        CHECK(theRunningServiceWeakRef != null);
        final TheService theRunningService = theRunningServiceWeakRef.get();
        CHECK(theRunningService != null);
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
    }  // synchronized(theRunningServiceLock)
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

      if (mVerboseLevel >= 1) Log.i(TAG, "                  attempting to force orientation to "+screenOrientationConstantToString(newScreenOrientationConstant));
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
      if (mVerboseLevel >= 1) Log.i(TAG, "                  attempting to unforce orientation");
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
