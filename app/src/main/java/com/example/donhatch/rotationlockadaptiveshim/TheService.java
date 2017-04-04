package com.example.donhatch.rotationlockadaptiveshim;

import android.app.AlertDialog;
import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.OrientationListener;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

public class TheService extends Service {
    public static TheService theRunningService = null;
    public boolean mHasBeenDestroyed = false;
    // omfg it has to be nonzero
    private final int AN_IDENTIFIER_FOR_THIS_NOTIFICATION_UNIQUE_WITHIN_THIS_APPLICATION = 1;

    int mVerboseLevel = 1; // 0: nothing, 1: major stuff, 2: every accelerometer event (lots)

    // These are static to avoid having to think about when the service isn't running.
    public static int mStaticDegrees = -1; // most recent value passed to onOrientationChanged listener of any TheService instance.
    public static boolean mStaticDegreesIsValid = false;
    public static int mStaticClosestCompassPoint = -1; // means invalid
    private android.view.OrientationEventListener mOrientationEventListener;
    private Runnable mCleanupDialog = null;

    private android.database.ContentObserver mAccelerometerRotationObserver = new android.database.ContentObserver(new android.os.Handler()) {
        // Per https://developer.android.com/reference/android/database/ContentObserver.html (probably not necessary though)
        @Override public void onChange(boolean selfChange) { onChange(selfChange, null); }
        @Override
        public void onChange(boolean selfChange, android.net.Uri uri) {
            System.out.println("            in TheService mAccelerometerRotationObserver onChange(selfChange="+selfChange+", uri="+uri+")");
            if (mStaticWhackAMole) {
                int oldACCELEROMETER_ROTATION = -1;
                try {
                    oldACCELEROMETER_ROTATION = Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION);
                } catch (Settings.SettingNotFoundException e) {
                    if (mVerboseLevel >= 1) System.out.println("          Settings.System.ACCELEROMETER_ROTATION was not found!?");
                }
                if (oldACCELEROMETER_ROTATION != 0) {
                    System.out.println("              WHACK!");
                    Toast.makeText(TheService.this, "WHACK! system autorotate got turned on, turning it back off", Toast.LENGTH_SHORT).show();

                    try {
                        Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0);
                    } catch (SecurityException e) {
                        // XXX dup code
                        if (mVerboseLevel >= 1) System.out.println("          Oh no, can't set system settings-- were permissions revoked?");
                        Toast.makeText(TheService.this, " Oh no, can't set system settings-- were permissions revoked?\nHere, please grant the permission.", Toast.LENGTH_SHORT).show();
                        Intent grantIntent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                        grantIntent.setData(android.net.Uri.parse("package:"+getPackageName()));
                        if (mVerboseLevel >= 1) System.out.println("              grantIntent = "+grantIntent);
                        if (mVerboseLevel >= 1) System.out.println("              calling startActivity with ACTION_MANAGE_WRITE_SETTINGS");
                        startActivity(grantIntent);
                        if (mVerboseLevel >= 1) System.out.println("              returned from startActivity with ACTION_MANAGE_WRITE_SETTINGS");
                    }
                }
            }
            System.out.println("            out TheService mAccelerometerRotationObserver onChange(selfChange="+selfChange+", uri="+uri+")");
        }
    };  // mAccelerometerRotationObserver

    public static boolean mStaticWhackAMole = true; // TODO: make this a shared preference? the activity is the one who sets this
    public static boolean mStaticAutoRotate = true; // TODO: make this a shared preference? the activity is the one who sets this
    public static boolean mStaticPromptFirst = true; // TODO: make this a shared preference? the activity is the one who sets this
    public static boolean mStaticOverride = false; // TODO: make this a shared preference? the activity is the one who sets this

    // Used as value of System.Settings.USER_ROTATION
    public static String surfaceRotationConstantToString(int surfaceRotationConstant) {
      switch (surfaceRotationConstant) {
        case android.view.Surface.ROTATION_0: return "ROTATION_0";
        case android.view.Surface.ROTATION_90: return "ROTATION_90";
        case android.view.Surface.ROTATION_180: return "ROTATION_180";
        case android.view.Surface.ROTATION_270: return "ROTATION_270";
        default: return "[unknown surface rotation constant "+surfaceRotationConstant+"]";
      }
    }


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
            case android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND: return "SCREEN_ORIENTATION_BEHIND";
            case android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR: return "SCREEN_ORIENTATION_FULL_SENSOR";
            case android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER: return "SCREEN_ORIENTATION_FULL_USER";
            case android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE: return "SCREEN_ORIENTATION_LANDSCAPE";
            case android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED: return "SCREEN_ORIENTATION_LOCKED";
            case android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR: return "SCREEN_ORIENTATION_NOSENSOR";
            case android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT: return "SCREEN_ORIENTATION_PORTRAIT";
            case android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE: return "SCREEN_ORIENTATION_REVERSE_LANDSCAPE";
            case android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT: return "SCREEN_ORIENTATION_REVERSE_PORTRAIT";
            case android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR: return "SCREEN_ORIENTATION_SENSOR";
            case android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE: return "SCREEN_ORIENTATION_SENSOR_LANDSCAPE";
            case android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT: return "SCREEN_ORIENTATION_SENSOR_PORTRAIT";
            case android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED: return "SCREEN_ORIENTATION_UNSPECIFIED";
            case android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER: return "SCREEN_ORIENTATION_USER";
            case android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE: return "SCREEN_ORIENTATION_USER_LANDSCAPE";
            case android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT: return "SCREEN_ORIENTATION_USER_PORTRAIT";
            default: return "[unknown screen orientation constant "+screenOrientationConstant+"]";
        }
    }
    // Actuallly one more namespace:
    //   (3) https://developer.android.com/reference/android/content/res/Configuration.html#orientation
    //   Configuration.orientation
    //   Configuration.ORIENTATION_PORTRAIT (1) or Configuration.ORIENTATION_LANDSCAPE (2)
    public static String orientationConstantToString(int orientationConstant) {
        switch (orientationConstant) {
            case android.content.res.Configuration.ORIENTATION_PORTRAIT: return "ORIENTATION_PORTRAIT";
            case android.content.res.Configuration.ORIENTATION_LANDSCAPE: return "ORIENTATION_LANDSCAPE";
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

    private void CHECK(boolean condition) {
        if (!condition) {
            throw new AssertionError("CHECK failed");
        }
    }

    public TheService() {
        if (mVerboseLevel >= 1) System.out.println("                    in TheService ctor");
        if (mVerboseLevel >= 1) System.out.println("                    out TheService ctor");
    }

    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Not implemented");
    }

    public class BetterToast {
        // This class allows making a toast of any given duration.
        // CAVEAT: it will interact well with other BetterToasts, but not regular toasts.
        // In particular, if a regular toast is being displayed,
        // this toast will get delayed in starting... but not in ending,
        // so it might not get displayed at all.
    }
    // Show toast for some length of time <= LENGTH_LONG (3500 millis).
    // BUG: if some other toast is up already, this one will get delayed in starting... but not in ending!  Argh.
    private Toast showToast(android.content.Context ctx, String text, long millis) {
        // LENGTH_SHORT is 2000 millis
        // LENGTH_LONG is 3500 millis
        final Toast toast = Toast.makeText(ctx, text, Toast.LENGTH_LONG);
        toast.show();

        android.os.Handler handler = new android.os.Handler();
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
        if (mVerboseLevel >= 1) System.out.println("                        in TheService.onCreate");
        if (theRunningService != null) {
            // XXX do this via a Notification, I think
            // XXX have I decided this never happens?  Not sure.
            throw new AssertionError("TheService.onCreate called when there's already an existing service!");
        }
        TheService.theRunningService = this;
        showToast(this, "Service Created", 1000); // it's about to change to "Service Started"
        mOrientationEventListener = new android.view.OrientationEventListener(this, android.hardware.SensorManager.SENSOR_DELAY_NORMAL) {
            @Override
            public void onOrientationChanged(int degrees) {
                if (mVerboseLevel >= 2) System.out.println("        in onOrientationChanged(degrees="+degrees+")");
                if (degrees == android.view.OrientationEventListener.ORIENTATION_UNKNOWN) { // -1
                    if (mVerboseLevel == 1) System.out.println("        in onOrientationChanged(degrees="+degrees+")");
                    if (mVerboseLevel >= 1) System.out.println("          (not doing anything)");
                    if (mVerboseLevel == 1) System.out.println("        out onOrientationChanged(degrees="+degrees+")");
                } else {
                    boolean closestCompassPointChanging = false;
                    if (mStaticClosestCompassPoint == -1) {
                        closestCompassPointChanging = true;
                    } else {
                        int distanceFromPreviousClosestCompassPoint = Math.abs(degrees - mStaticClosestCompassPoint);
                        if (distanceFromPreviousClosestCompassPoint > 180)
                            distanceFromPreviousClosestCompassPoint = Math.abs(distanceFromPreviousClosestCompassPoint - 360);
                        if (mVerboseLevel >= 2) System.out.println("          old mStaticClosestCompassPoint = " + mStaticClosestCompassPoint);
                        if (mVerboseLevel >= 2) System.out.println("          distanceFromPreviousClosestCompassPoint = " + distanceFromPreviousClosestCompassPoint);


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

                    // TODO: check whether the value we'd change USER_ROTATION to is the same as the current value.  If so, just change mStaticClosestCompassPoint, no need to pop up dialog or anything.

                    if (closestCompassPointChanging) {
                        if (mVerboseLevel == 1) System.out.println("        in onOrientationChanged(degrees="+degrees+")"); // upgrade verbosity threshold from 2 to 1
                        int oldClosestCompassPoint = mStaticClosestCompassPoint;
                        int newClosestCompassPoint = degrees < 45 ? 0 : degrees < 135 ? 90 : degrees < 225 ? 180 : degrees < 315 ? 270 : 0;
                        mStaticClosestCompassPoint = newClosestCompassPoint;

                        if (mStaticAutoRotate) {
                            if (mStaticPromptFirst) {

                                if (mCleanupDialog != null) {
                                    mCleanupDialog.run();
                                    mCleanupDialog = null;
                                }

                                if (true) {
                                    // https://developer.android.com/guide/topics/ui/dialogs.html
                                    // For custom layout, use setView on the builder:
                                    // but all the examples in the world use xml layouts. what a huge waste of time.
                                    // maybe this one uses a view?
                                    // https://www.codota.com/android/methods/android.app.AlertDialog.Builder/setView
                                    // Maybe this has something?  http://iserveandroid.blogspot.com/2011/04/how-to-dismiss-your-non-modal-dialog.html

                                    if (mVerboseLevel == 1) System.out.println("          attempting to pop up an AlertDialog");

                                    // Don't use an AlertDialog.Builder, since that's incompatible with custom onTouchEvent.
                                    // (TODO:  although... could I use a View.OnTouchListener insted?)
                                    final AlertDialog alertDialog = new AlertDialog(TheService.this) {
                                        @Override
                                        public boolean onTouchEvent(MotionEvent motionEvent) {
                                            if (mVerboseLevel == 1) System.out.println("            in alertDialog onTouchEvent");
                                            if (mVerboseLevel == 1) System.out.println("              motionEvent.getActionMasked()="+motionEventActionMaskedConstantToString(motionEvent.getActionMasked()));
                                            if (motionEvent.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                                                if (mVerboseLevel == 1) System.out.println("              touch outside dialog! cancelling");
                                                CHECK(mCleanupDialog != null); // XXX not completely confident in this
                                                mCleanupDialog.run();
                                                mCleanupDialog = null;
                                            } else {
                                                if (mVerboseLevel == 1) System.out.println("              touch inside dialog; ignoring");
                                            }
                                            if (mVerboseLevel == 1) System.out.println("            out alertDialog onTouchEvent");
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
                                    alertDialog.setButton(AlertDialog.BUTTON_NEGATIVE, "No", new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int id) {
                                            if (mVerboseLevel == 1) System.out.println("            in alertDialog negative button onClick");
                                            // XXX is this evidence for why it's good to always delay?
                                            CHECK(mCleanupDialog != null);
                                            mCleanupDialog.run();
                                            mCleanupDialog = null;
                                            if (mVerboseLevel == 1) System.out.println("            out alertDialog negative button onClick");
                                        };
                                    });
                                    alertDialog.setButton(AlertDialog.BUTTON_POSITIVE, "Yes", new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int id) {
                                            if (mVerboseLevel == 1) System.out.println("            in alertDialog positive button onClick");
                                            // XXX is this evidence for why it's good to always delay?
                                            CHECK(mCleanupDialog != null);
                                            mCleanupDialog.run();
                                            mCleanupDialog = null;
                                            doTheAutoRotateThingNow();
                                            if (mVerboseLevel == 1) System.out.println("            out alertDialog positive button onClick");
                                        };
                                    });


                                    Window alertDialogWindow = alertDialog.getWindow();
                                    alertDialogWindow.setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT); // required when context=TheService.this, otherwise WindowManager.BadTokenException.
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
                                    } catch (android.view.WindowManager.BadTokenException e) {
                                        // This happens if I omit android.permission.SYSTEM_ALERT_WINDOW from AndroidManifest, or if user revoke or didn't grant "Draw over other apps".
                                        // TODO: need to do the double-opt-in dance here, similar to the WRITE_SETTINGS permission
                                        CHECK(false);
                                    }
                                    // Make it expire in 3 seconds.
                                    final Handler handler = new Handler();
                                    final Runnable runnable = new Runnable() {
                                        @Override
                                        public void run() {
                                            if (mVerboseLevel == 1) System.out.println("            in run: prompt expired; canceling alert dialog");
                                            alertDialog.cancel();
                                            mCleanupDialog = null;
                                            if (mVerboseLevel == 1) System.out.println("            out run: prompt expired; cancelled alert dialog");
                                        }
                                    };
                                    handler.postDelayed(runnable, 3*1000);

                                    CHECK(mCleanupDialog == null);
                                    mCleanupDialog = new Runnable() {
                                        @Override
                                        public void run() {
                                            if (mVerboseLevel == 1) System.out.println("            cleaning up previous dialog");
                                            alertDialog.cancel();
                                            handler.removeCallbacks(runnable); // ok if it wasn't scheduled
                                            if (mVerboseLevel == 1) System.out.println("            cleaned up previous dialog");
                                        }
                                    };

                                    // Hacky countdown in the dialog.
                                    // These don't get cleaned up by mCleanupDialog, but it doesn't matter; they don't hurt anything.
                                    new Handler().postDelayed(new Runnable() { @Override public void run() { alertDialog.setMessage("2..."); } }, 1*1000);
                                    new Handler().postDelayed(new Runnable() { @Override public void run() { alertDialog.setMessage("1..."); } }, 2*1000);

                                    if (mVerboseLevel == 1) System.out.println("          attempted to pop up an AlertDialog");
                                }
                                if (false) {
                                    if (mVerboseLevel == 1) System.out.println("          attempting to pop up a semitransparent icon!");
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
                                            if (mVerboseLevel == 1) System.out.println("            in onDraw");
                                            super.onDraw(canvas);
                                            canvas.drawText("test test test", 0, 100, mPaint);
                                            if (mVerboseLevel == 1) System.out.println("            out onDraw");
                                        }

                                        @Override
                                        protected void onAttachedToWindow() {
                                            if (mVerboseLevel == 1) System.out.println("            in onAttachedToWindow");
                                            super.onAttachedToWindow();
                                            if (mVerboseLevel == 1) System.out.println("            out onAttachedToWindow");
                                        }

                                        @Override
                                        protected void onDetachedFromWindow() {
                                            if (mVerboseLevel == 1) System.out.println("            in onDetachedFromWindow");
                                            super.onDetachedFromWindow();
                                            if (mVerboseLevel == 1) System.out.println("            out onDetachedFromWindow");
                                        }

                                        @Override
                                        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                                            if (mVerboseLevel == 1) System.out.println("            in onMeasure");
                                            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                                            if (mVerboseLevel == 1) System.out.println("            out onMeasure");
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
                                    } catch (android.view.WindowManager.BadTokenException e) {
                                         // This happens if I omit android.permission.SYSTEM_ALERT_WINDOW from AndroidManifest
                                         // TODO: needs the double-opt-in dance here, similar for the WRITE_SETTINGS permission
                                         CHECK(false);
                                    }

                                    // Make it expire in 3 seconds.
                                    new Handler().postDelayed(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (mVerboseLevel == 1) System.out.println("            in run: prompt expired");
                                            windowManager.removeView(myView);
                                            if (mVerboseLevel == 1) System.out.println("            out run: prompt expired");
                                        }
                                    }, 3*1000);

                                    if (mVerboseLevel == 1) System.out.println("          attempted to pop up a semitransparent icon!");
                                }
                            } else {
                                if (mVerboseLevel >= 1) System.out.println("          calling doTheAutoRotateThing");
                                doTheAutoRotateThingNow();
                                if (mVerboseLevel >= 1) System.out.println("          returned from doTheAutoRotateThing");
                            }
                        }
                        if (mVerboseLevel >= 1) System.out.println("          new mStaticClosestCompassPoint = " + mStaticClosestCompassPoint);
                        if (mVerboseLevel == 1) System.out.println("        out onOrientationChanged(degrees="+degrees+")"); // upgrade verbosity threshold from 2 to 1
                    } else {
                        if (mVerboseLevel >= 2) System.out.println("          (no change)");
                    }
                }
                int oldDegrees = mStaticDegrees;
                int newDegrees = degrees;
                mStaticDegrees = newDegrees;
                mStaticDegreesIsValid = true;
                {
                    // Pretty lame to do this if there's no activity listening,
                    // but I guess this is the standard way to do it.
                    // CBB: think about whether it makes sense to have a "numBroadcastListeners" variable,
                    // and if it's zero, don't bother to send
                    Intent intent = new Intent("degrees changed");
                    intent.putExtra("oldDegrees", oldDegrees);
                    intent.putExtra("newDegrees", newDegrees);
                    android.support.v4.content.LocalBroadcastManager.getInstance(TheService.this).sendBroadcast(intent);
                }

                if (mVerboseLevel >= 2) System.out.println("        out onOrientationChanged(degrees="+degrees+")");
            }
        };  // mOrientationEventListener


        // same thing we do on ACTION_SCREEN_ON (dup code)
        if (mOrientationEventListener.canDetectOrientation() == true) {
            if (mVerboseLevel >= 1) System.out.println("                          can detect orientation, enabling orientation event listener");
            mOrientationEventListener.enable();
        } else {
            if (mVerboseLevel >= 1) System.out.println("                          cannot detect orientation, disabling orientation event listener");
            mOrientationEventListener.disable();
        }

        {
            // Note that some phones accelerometers work in standby mode (screen off), some don't:
            //     http://www.saltwebsites.com/2012/android-accelerometers-screen-off
            // For the ones that do, we want to stop listening to the accelerometer when the screen is off,
            // to conserve battery.
            // https://thinkandroid.wordpress.com/2010/01/24/handling-screen-off-and-screen-on-intents/
            System.out.println("android.content.Intent.ACTION_SCREEN_OFF = "+android.content.Intent.ACTION_SCREEN_OFF);
            System.out.println("android.content.Intent.ACTION_SCREEN_ON = "+android.content.Intent.ACTION_SCREEN_ON);

            android.content.IntentFilter intentFilter = new android.content.IntentFilter();
            intentFilter.addAction(android.content.Intent.ACTION_SCREEN_OFF);
            intentFilter.addAction(android.content.Intent.ACTION_SCREEN_ON);
            intentFilter.addAction(android.content.Intent.ACTION_USER_PRESENT);
            registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (mVerboseLevel >= 1) System.out.println("        in onReceive(intent.getAction()="+intent.getAction()+")");
                    if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                        if (mVerboseLevel >= 1) System.out.println("          ACTION_SCREEN_OFF: disabling orientation event listener");
                        mOrientationEventListener.disable();
                    } else if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                        // same thing we do on create (dup code)
                        if (mOrientationEventListener.canDetectOrientation() == true) {
                            if (mVerboseLevel >= 1) System.out.println("          ACTION_SCREEN_ON and can detect orientation, enabling orientation event listener");
                            mOrientationEventListener.enable();
                        } else {
                            if (mVerboseLevel >= 1) System.out.println("          ACTION_SCREEN_ON but cannot detect orientation, disabling orientation event listener");
                            mOrientationEventListener.disable();
                        }
                    } else if (intent.getAction().equals(Intent.ACTION_USER_PRESENT)) {
                        if (mVerboseLevel >= 1) System.out.println("          ACTION_USER_PRESENT");
                        // XXX TODO: figure out whether there's something meaningful to do here
                    } else {
                        // This shouldn't happen
                        System.out.println("          received unexpected broadcast: "+intent.getAction()+" (this shouldn't happen)");
                        CHECK(false);
                    }
                    if (mVerboseLevel >= 1) System.out.println("        out onReceive(intent.getAction()="+intent.getAction()+")");
                };
            }, intentFilter);
        }

        getContentResolver().registerContentObserver(Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION), false, mAccelerometerRotationObserver);
        if (mVerboseLevel >= 1) System.out.println("                        out TheService.onCreate");

    }

    @Override
    public int onStartCommand(Intent startIntent, int flags, int startId) {
        if (mVerboseLevel >= 1) System.out.println("                            in TheService.onStartCommand(startIntent, flags="+flags+", startId="+startId+")");
        if (mVerboseLevel >= 1) System.out.println("                              startIntent = "+startIntent);

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
            android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(this, 0, notificationIntent, 0);
            final android.app.Notification.Builder builder = new android.app.Notification.Builder(this)
                    .setContentTitle("Adaptive Rotation Lock Shim") // XXX R.string.notification_title
                    .setContentText("Mode: the only mode I know") // XXX R.string.notification_messsage
                    .setSmallIcon(R.drawable.typewriter_el)
                    .setContentIntent(pendingIntent)
                    //.setOngoing(true) // XXX doesn't seem to help keep the icon up
                    .setWhen(System.currentTimeMillis()+10*60*1000)
                    .setShowWhen(true)
                    ;
            final android.app.Notification notification = builder.build();
            //notification.flags |= Notification.FLAG_NO_CLEAR; // XXX doesn't seem to help keep the icon up
            if (mVerboseLevel >= 1) System.out.println("                              calling startForeground");
            startForeground(AN_IDENTIFIER_FOR_THIS_NOTIFICATION_UNIQUE_WITHIN_THIS_APPLICATION, notification);
            if (mVerboseLevel >= 1) System.out.println("                              returned from startForeground");
            if (false) {
                if (mVerboseLevel >= 1) System.out.println("                              calling stopForeground");
                stopForeground(AN_IDENTIFIER_FOR_THIS_NOTIFICATION_UNIQUE_WITHIN_THIS_APPLICATION);
                if (mVerboseLevel >= 1) System.out.println("                              returned from stopForeground");
            }


            // XXX what is this?  can I remove it?  maybe it was just an experiment to see if I can successfully update the notification text?  hmm.
            if (true) {
                final int count[] = {0};
                final android.os.Handler handler = new android.os.Handler();
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (mHasBeenDestroyed) {
                            // stop updating the notification!
                            // TODO: We actually shouldn't have gotten this far; should make onDestroy() cancel us.
                            if (mVerboseLevel >= 1) System.out.println("service has been destroyed! abandoning notification update");
                            return;
                        }
                        builder.setContentText("updated "+(count[0]++));
                        if (false) {
                            android.app.NotificationManager notificationManager = (android.app.NotificationManager) getApplicationContext().getSystemService(getApplicationContext().NOTIFICATION_SERVICE);
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

        Toast.makeText(this, "Service Started", Toast.LENGTH_SHORT).show();

        if (mStaticAutoRotate) {
            // Make sure we don't fight with the system's ACCELEROMETER_ROTATION.
            // This is the same as user doing Settings -> Display -> "When device is rotated": "Stay in portrait view".
            // (actually that sets USER_ROTATION to 0 too, if it wasn't 0).
            Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0);
        }

        if (mVerboseLevel >= 1) System.out.println("                            out TheService.onStartCommand(startIntent, flags="+flags+", startId="+startId+")");
        return START_STICKY; // Continue running until explicitly stopped, and restart the app process with service only if it gets kill by e.g. stopsign button in Android Monitor in AS
    }

    @Override
    public void onDestroy() {
        if (mVerboseLevel >= 1) System.out.println("                        in TheService.onDestroy");
        Toast.makeText(this, "Service Destroyed", Toast.LENGTH_SHORT).show();
        getContentResolver().unregisterContentObserver(mAccelerometerRotationObserver);
        mAccelerometerRotationObserver = null;
        mOrientationEventListener.disable();
        mOrientationEventListener = null;
        theRunningService = null;
        mHasBeenDestroyed = true;
        // Reasons we might be getting stopped while activity is still running:
        // - user toggled the switch in the activity
        // - something else called stopService
        // - in the latter case, we must tell the activity, otherwise the switch will not be in sync.
        // XXX race? what if user is in the process of turning it on??
        if (mVerboseLevel >= 1) System.out.println("                        out TheService.onDestroy");
    }

    // Syncs system USER_ROTATION to mStaticClosestCompassPoint.
    // Also whacks ACCELEROMETER_ROTATION if set (but it shouldn't be).
    private void doTheAutoRotateThingNow() {
        if (mVerboseLevel >= 1) System.out.println("            in doTheAutoRotateThingNow");
        int oldACCELEROMETER_ROTATION = -1;
        int oldUSER_ROTATION = -1;
        try {
            oldACCELEROMETER_ROTATION = Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION);
        } catch (Settings.SettingNotFoundException e) {
            if (mVerboseLevel >= 1) System.out.println("          Settings.System.ACCELEROMETER_ROTATION was not found!?");
        }
        try {
            oldUSER_ROTATION = Settings.System.getInt(getContentResolver(), Settings.System.USER_ROTATION);
        } catch (Settings.SettingNotFoundException e) {
            if (mVerboseLevel >= 1) System.out.println("          Settings.System.USER_ROTATION was not found!?");
        }
        if (oldACCELEROMETER_ROTATION != 0) {
          // In case this got turned on for some reason.
          // TODO: maybe this isn't needed any more now that we have the whack-a-mole listener?  I think this will *probably* never happen.  Maybe it depends on what order listener callbacks are invoked in?
          // XXX actually need to do this much sooner! so that system won't get to it first.  (I think?  Not sure what I was saying)
          if (mVerboseLevel >= 1) System.out.println("          changing Settings.System.ACCELEROMETER_ROTATION from " + oldACCELEROMETER_ROTATION + " to 0 !!!!!!!!!!");
          Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0);
        } else {
          if (mVerboseLevel >= 1) System.out.println("          Settings.System.ACCELEROMETER_ROTATION was 0 as expected");
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

        int newUSER_ROTATION = -1;
        if (mStaticClosestCompassPoint == 0) {
            newUSER_ROTATION = android.view.Surface.ROTATION_0;
        } else if (mStaticClosestCompassPoint == 90) {
            newUSER_ROTATION = android.view.Surface.ROTATION_270;
        } else if (mStaticClosestCompassPoint == 180) {
            newUSER_ROTATION = android.view.Surface.ROTATION_180;
        } else if (mStaticClosestCompassPoint == 270) {
            newUSER_ROTATION = android.view.Surface.ROTATION_90;
        }
        CHECK(newUSER_ROTATION != -1); // logical assertion
        try {
        if (mVerboseLevel >= 1) System.out.println("          changing Settings.System.USER_ROTATION from " + surfaceRotationConstantToString(oldUSER_ROTATION) + " to " + surfaceRotationConstantToString(newUSER_ROTATION));
        Settings.System.putInt(getContentResolver(), Settings.System.USER_ROTATION, newUSER_ROTATION);
        } catch (SecurityException e) {
            if (mVerboseLevel >= 1) System.out.println("          Oh no, can't set system settings-- were permissions revoked?");
            Toast.makeText(TheService.this, " Oh no, can't set system settings-- were permissions revoked?\nHere, please grant the permission.", Toast.LENGTH_SHORT).show();
            Intent grantIntent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
            grantIntent.setData(android.net.Uri.parse("package:"+getPackageName()));
            if (mVerboseLevel >= 1) System.out.println("              grantIntent = "+grantIntent);
            if (mVerboseLevel >= 1) System.out.println("              calling startActivity with ACTION_MANAGE_WRITE_SETTINGS");
            startActivity(grantIntent);
            if (mVerboseLevel >= 1) System.out.println("              returned from startActivity with ACTION_MANAGE_WRITE_SETTINGS");
        }
        if (mVerboseLevel >= 1) System.out.println("            out doTheAutoRotateThingNow");
    }  // doTheAutoRotateThingNow
}
