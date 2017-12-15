// TODO: service icon isn't right, it's solid white (only on recent target and/or runtime I think?) (t27: r25 square, r26 circle, r27 circle)
// TODO: sort out the notification channel stuff (slowly drag notification to left to see what's going on, it's weird)
// TODO: make it work properly when activity is destroyed&recreated on orientation change?
//       - the onConfigurationChanged logic (i.e. reorganizing layout slightly) doesn't happen if I do it that way
// TODO: look into the consequences of singleinstance and/or android:launchMode="singleTask" https://stackoverflow.com/questions/37709918/warning-do-not-place-android-context-classes-in-static-fields-this-is-a-memory/37709963#comment-77492138  will different values let me exercise possibilities I have not been able to exercise so far? like two activities at once

// TODO: avoid drawArc since it requires minSdkVersion>=21; bake in the arcs instead
// BUG: upgrading targetSdkVersion from 25 to 26 makes the app icon a solid white circle?? wtf?
//     maybe relevant:
//      https://stackoverflow.com/questions/45611508/icons-look-flat-in-action-bar#answer-46474450
//     which references:
//      https://stackoverflow.com/questions/45340740/squashed-icons-in-toolbar-after-change-to-android-sdk-26/45344964#45344964
//     ah I see, I think it needs something in xxxhdpi.
//     How did I generate the L icons to begin with?
//     (I regenerated... argh, lost transparent backgrounds)

// BUG: after switching orientation, dial rotation is 90 degrees wrong til it changes. need to update rotation immediately.  (very obvious in emulator)
// BUG: in emulator, when screen layout changes, dial orientation doesn't get updated.  I didn't notice this on real device, because accelerometer event comes in almost immediately.
// BUG: why does there seem to be a delay after I click "yes" and before it rotates? seems more responsive when it's not prompting
// BUG: when turning *off* overlay and it's red, it flashes off-on-off
// BUG: if service is started while permissions aren't there (say, were revoked),
//       process crashes.  Need to catch this and do something better I think.
// BUG: if permission revoked in midstream and double-opt-in-dance is done,
//       if activity isn't up, and it's the first time,
//       the system settings screen is (sometimes) delayed until after the toast disappears!
// TODO: better communication from service to activity:
//         - ui should monitor the overlay state: whether it's visible, and its getRequestedOrientation
// TODO: enhance ui look
//         - Put title at top of activity screen
//         - uncrowd ui
//         - put bottom stuff in "advanced" or "debug" or "devel" section, closed by default
//         - put current line ("up" if static dial, towards screen top if moving dial) and current effective quadrant bounds on dial, in red probably
//         - maybe option for different dial display style, where numbers stay with screen and only up vector moves?  (would have to reverse the numbers on the dial)
// TODO: enhance ui functionality
//         - toggle switch to turn on/off dial?
//         - make listened/polled values turn red when they change and then fade to black? hmm
//         - control verbosity levels
// TODO: actually make it work correctly when activity restarts due to orientation: remove the thing from the manifest? maybe worth a try
// TODO: if permission got revoked and we re-do the double-opt-in-dance, we end up not having written the value... I think? have to think about the consequences. maybe not too bad.
// TODO: ask question on stackoverflow about interaction between
//           setRequestedOrientation()
//           ACCELEROMETER_ROTATION
//           USER_ROTATION
//       The following may be useful:
//           https://vaneyckt.io/posts/programmatically_rotating_the_android_screen/
//       unfortunately, it can only control accelerometer_rotation and user_rotation.
//       Is there a way to query the windowmanager's stack of views?
// TODO: ask on stackoverflow whether there are any downsides
//       to communicating by calling methods on the singleton
// TODO: ask on stackoverflow how to create matrix

/*
wow, I asked this as https://stackoverflow.com/questions/43319781/how-to-test-exercise-androids-screen-rotation-behavior and got absolutely no response
Q: how to test/exercise android's screen rotation behavior?
tags: android

I'd like to test android's behavior on all possible combinations of the following "inputs":

- top activity's [`setRequestedOrientation()`](https://developer.android.com/reference/android/app/Activity.html#setRequestedOrientation(int)) ([15 possible values, not including `SCREEN_ORIENTATION_BEHIND`](https://developer.android.com/reference/android/R.attr.html#screenOrientation))
- [`Settings.System.ACCELEROMETER_ROTATION`](https://developer.android.com/reference/android/provider/Settings.System.html#ACCELEROMETER_ROTATION) (2 possible values)
- [`Settings.System.USER_ROTATION`](https://developer.android.com/reference/android/provider/Settings.System.html#USER_ROTATION) ([4 possible values](https://developer.android.com/reference/android/view/Surface.html#ROTATION_0))
- device's physical orientation (queryable by [`OrientationEventListener`](https://developer.android.com/reference/android/view/OrientationEventListener.html)) (4 possible quadrants)

Specifically, I want to see how the inputs affect the following "output":

- `getWindowManager().getDefaultDisplay()`[`.getRotation()`](https://developer.android.com/reference/android/view/Display.html#getRotation()) ([4 possible values](https://developer.android.com/reference/android/view/Surface.html#ROTATION_0))

So this will require testing at least all 15*2*4*4=480 possible input states.

Additionally, since rotation behavior is often dependent on the *history*
of the inputs (not just the *current* input values),
I want to test (at least) all possible transitions from one input state to an "adjacent" input state,
i.e. to an input state that differs from the given input state by one input parameter.
The number of such input state transitions is:

<pre>
      (number of input states) * (number of states adjacent to a given input state)
    = (15*2*4*4) * ((15-1) + (2-1) + (4-1) + (4-1))
    = 480 * 21
    = 10080
</pre>

Furthermore, sometimes output is dependent on the previous *output* as well as previous and current
input (e.g. `SCREEN_ORIENTATION_LOCKED`, `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`).
The number of possible outputs for a given input state can be between 1 and 4,
so this multiplies the number of transitions that must be tested by up to 4:

<pre>
    10080 * 4 = 40320
</pre>

That's a lot of transitions to test, so the testing would have to be programmatic/scripted.
Three out of the four input params are straightforward to control programmatically;
the one that's not straightforward to control is the device's physical orientation.

So, how would one go about scripting it?  I can think of the following approaches.

**Approach #1**:  Replace the (physical or emulated) device's accelerometer with a scriptable mock accelerometer
for the duration of the test.   But, if I understand correctly, mock accelerometers do not exist.

**Approach #2**:  Use the android emulator, and script pressing of the "rotate counterclockwise" and
"rotate clockwise" buttons using an interaction automation tool on the host machine (e.g. applescript / autohotkey / xdotool).

Any other ideas?

*/



package com.example.donhatch.rotationlockadaptiveshim;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

@SuppressWarnings({"ConstantIfStatement", "PointlessArithmeticExpression", "ConstantConditions"})
public class TheActivity extends Activity {

    private static final String TAG = "RLAS activity";  // was "RotationLockAdaptiveShim activity" but got warnings
    private static void CHECK(boolean condition) {
        if (!condition) {
            throw new AssertionError("CHECK failed");
        }
    }

    // We set this temporarily while setting the checkbox from the program,
    // so that the onCheckedChanged listener can tell that's what happened.
    private boolean mSettingCheckedFromProgram = false;

    private long mNumUpdates = 0;
    private boolean mPolling = false;  // TODO: make this a shared preference so it will persist more persistently?  (note that it *does* persist somewhat, even from one process to the next, as long as the system is keeping the bundle which contains toggle states, interesting)
    private int mMostRecentConfigurationOrientation = -1;  // from most recent onConfigurationChanged, or getResources().getConfiguration().orientation initially

    // This is no longer used by the service (good!) so doesn't need to be public.  It's now just used
    // as a sanity check.
    private static TheActivity theRunningActivity = null; // actually not sure there is guaranteed to be at most one

    private Handler mPollingHandler = new Handler();
    private Runnable mPollingRunnable = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "                in once-per-second poll (this should only happen when ui is visible), mNumUpdates is now "+mNumUpdates);
            updateAccelerometerOrientationDegreesTextView();
            updatePolledStatusTextView();
            mPollingHandler.postDelayed(this, 1*1000);  // call again 1 second later
            Log.i(TAG, "                out once-per-second poll (this should only happen when ui is visible), mNumUpdates is now "+mNumUpdates);
        }
    };

    // https://stackoverflow.com/questions/6178896/how-to-draw-a-line-in-imageview-on-android
    // This is actually pretty lame, since, it turns out, onDraw is only called once
    // on startup (and orientation change between portrait and landscape), and subsequently the drawn thing gets rotated.
    public class MyImageView1 extends android.support.v7.widget.AppCompatImageView {
        public MyImageView1(Context context) {
            super(context);
            // TODO: Auto-generated constructor stub
        }
        @Override
        protected void onDraw(Canvas canvas) {
          Log.i(TAG, "                in MyImageView1.onDraw");
          Log.i(TAG, "                  canvas.getWidth() = "+canvas.getWidth());
          Log.i(TAG, "                  canvas.getHeight() = "+canvas.getHeight());
          // portrait: 1328x2100
          // landscape: 2100x1230
          super.onDraw(canvas);
          canvas.drawColor(Color.WHITE);  // erase the dial image (sort of silly)

          Paint redPaint = new Paint(Paint.ANTI_ALIAS_FLAG) {{
            setColor(Color.RED);
            setARGB(255, 255, 192, 192);
          }};

          Paint orangeishYellowPaint = new Paint(Paint.ANTI_ALIAS_FLAG) {{
            setColor(Color.YELLOW);
            //setARGB(255, 255, 224, 192);
            //setARGB(255, 255, 240, 192);
            setARGB(255, 255, 248, 192);
          }};

          Paint yellowPaint = new Paint(Paint.ANTI_ALIAS_FLAG) {{
            setColor(Color.YELLOW);
            setARGB(255, 255, 255, 192);
          }};

          Paint greenishYellowPaint = new Paint(Paint.ANTI_ALIAS_FLAG) {{
            setColor(Color.YELLOW);
            //setARGB(255, 224, 255, 192);
            //setARGB(255, 240, 255, 192);
            setARGB(255, 248, 255, 192);
          }};

          Paint greenPaint = new Paint(Paint.ANTI_ALIAS_FLAG) {{
            setColor(Color.GREEN);
            setARGB(255, 192, 255, 192);
          }};

          Paint zeroLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG) {{
            setARGB(255, 255, 255, 255);  // opaque white
            setStrokeWidth(3);
          }};

          float centerX = canvas.getWidth() / 2;
          float centerY = canvas.getHeight() / 2;
          float r = Math.min(centerX, centerY);

          //double hysteresis = 0.;
          double hysteresis = 22.5; // XXX must match what's in TheService, should be a member var or constant
          //double hysteresis = 45;

          // TODO: bake the colored sectors (the drawArc stuff) into the image file instead, so it will work on <21
          if (Build.VERSION.SDK_INT >=21) { // (runtime) otherwise drawArc doesn't exist
                canvas.drawArc(centerX-r, centerY-r,
                               centerX+r, centerY+r,
                               0.f, 360.f,
                               /*useCenter=*/true,
                               redPaint);

                if (false) {
                    // Solid yellow
                    canvas.drawArc(centerX-r, centerY-r,
                                   centerX+r, centerY+r,
                                   (float)(135. - hysteresis), (float)(90. + 2*hysteresis),
                                   /*useCenter=*/true,
                                   yellowPaint);
                } else {
                    // Two subtlely different shades of yellow, transitioning at 45 degrees
                    canvas.drawArc(centerX-r, centerY-r,
                                   centerX+r, centerY+r,
                                   (float)(135. - hysteresis), (float)(90. + 2*hysteresis),
                                   /*useCenter=*/true,
                                   orangeishYellowPaint);
                    canvas.drawArc(centerX-r, centerY-r,
                                   centerX+r, centerY+r,
                                   135.f, 90.f,
                                   /*useCenter=*/true,
                                   greenishYellowPaint);
                }

                canvas.drawArc(centerX-r, centerY-r,
                               centerX+r, centerY+r,
                               (float)(135. + hysteresis), (float)(90. - 2*hysteresis),
                               /*useCenter=*/true,
                               greenPaint);

                canvas.drawLine(centerX-r, centerY,
                                centerX, centerY,
                                zeroLinePaint);
          }
          Log.i(TAG, "                out MyImageView1.onDraw");
        }
        @Override
        public void setRotation(float newRotation) {
          Log.i(TAG, "                in MyImageView1.setRotation(newRotation="+newRotation+")");
          super.setRotation(newRotation);
          Log.i(TAG, "                out MyImageView1.setRotation(newRotation="+newRotation+")");
        }
    }  // class MyImageView1
    public class MyImageView2 extends android.support.v7.widget.AppCompatImageView {
        public MyImageView2(Context context) {
            super(context);
            // TODO: Auto-generated constructor stub
        }
        @Override
        protected void onDraw(Canvas canvas) {
          Log.i(TAG, "                in MyImageView2.onDraw");
          //super.onDraw(canvas); // don't bother, since it's the dial image which we don't want here
          Paint blackPaint = new Paint(Paint.ANTI_ALIAS_FLAG) {{
            setColor(Color.BLACK);
            setStrokeWidth(10);
          }};

          float centerX = canvas.getWidth() / 2;
          float centerY = canvas.getHeight() / 2;
          float r = Math.min(centerX, centerY);

          canvas.drawLine(centerX-r, centerY,
                          centerX, centerY,
                          blackPaint);

          Log.i(TAG, "                out MyImageView2.onDraw");
        }
        @Override
        public void setRotation(float newRotation) {
          Log.i(TAG, "                in MyImageView2.setRotation(newRotation="+newRotation+")");
          super.setRotation(newRotation);
          Log.i(TAG, "                out MyImageView2.setRotation(newRotation="+newRotation+")");
        }
    }  // class MyImageView2

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals("degrees changed")) {
                //Log.i(TAG, "                in onReceive: "+intent.getAction());
                int oldDegrees = intent.getIntExtra("old degrees", -100);
                int newDegrees = intent.getIntExtra("new degrees", -100);
                double oldDegreesSmoothed = intent.getDoubleExtra("old degrees smoothed", -100.);
                double newDegreesSmoothed = intent.getDoubleExtra("new degrees smoothed", -100.);
                //Log.i(TAG, "                  intent.getDoubleExtra(\"old degrees\") = "+oldDegrees);
                //Log.i(TAG, "                  intent.getDoubleExtra(\"new degrees\") = "+newDegrees);
                //Log.i(TAG, "                  intent.getDoubleExtra(\"old degrees smoothed\") = "+oldDegreesSmoothed);
                //Log.i(TAG, "                  intent.getDoubleExtra(\"new degrees smoothed\") = "+newDegreesSmoothed);
                TextView theAccelerometerOrientationDegreesTextView = findViewById(R.id.theAccelerometerOrientationDegreesTextView);
                theAccelerometerOrientationDegreesTextView.setText("  accelerometer degrees (most recent update): "+oldDegrees+" -> "+newDegrees);

                if (true) {
                    ImageView theDialImageView1 = findViewById(R.id.theDialImageView1);
                    ImageView theDialImageView = findViewById(R.id.theDialImageView);
                    ImageView theDialImageView2 = findViewById(R.id.theDialImageView2);
                    if (newDegrees == -1) {
                        theDialImageView2.setVisibility(View.INVISIBLE);
                    } else {
                        theDialImageView2.setVisibility(View.VISIBLE);

                        //double newDialRotation = 90. - newDegreesSmoothed;  // XXX what was I thinking?  doesn't match behavior
                        double newDialRotation = 90. - newDegrees;

                        int currentDisplayRotation = getWindowManager().getDefaultDisplay().getRotation();
                        // sign determined by dog science
                        switch (currentDisplayRotation) {
                            case Surface.ROTATION_0: newDialRotation -= 0; break;
                            case Surface.ROTATION_90: newDialRotation -= 90; break;
                            case Surface.ROTATION_180: newDialRotation -= 180; break;
                            case Surface.ROTATION_270: newDialRotation -= 270; break;
                            default: CHECK(false);
                        }

                        if (false) {
                          // Rotate the needle (theDialImageView2) to "up"
                          theDialImageView2.setRotation((float)newDialRotation);
                        } else {
                          // Keep the needle fixed, but rotate the dial
                          theDialImageView1.setRotation((float)newDialRotation);
                          theDialImageView.setRotation((float)newDialRotation);
                        }
                    }
                }

                //Log.i(TAG, "                out onReceive: "+intent.getAction());
            } else if (intent.getAction().equals("mStaticClosestCompassPoint changed")) {
                Log.i(TAG, "                in onReceive: "+intent.getAction());
                int oldClosestCompassPoint = intent.getIntExtra("old mStaticClosestCompassPoint", -100);
                int newClosestCompassPoint = intent.getIntExtra("new mStaticClosestCompassPoint", -100);
                Log.i(TAG, "                  intent.getIntExtra(\"old mStaticClosestCompassPoint\") = "+oldClosestCompassPoint);
                Log.i(TAG, "                  intent.getIntExtra(\"new mStaticClosestCompassPoint\") = "+newClosestCompassPoint);
                /* (it's commented out in the layout too, made things too crowded)
                TextView theClosestCompassPointTextView = findViewById(R.id.theClosestCompassPointTextView);
                theClosestCompassPointTextView.setText("  TheService.mStaticClosestCompassPoint: "+oldClosestCompassPoint+" -> "+newClosestCompassPoint);
                */
                Log.i(TAG, "                out onReceive: "+intent.getAction());
            } else if (intent.getAction().equals("mStaticPromptFirst changed")) {
                Log.i(TAG, "                in onReceive: "+intent.getAction());
                boolean newStaticPromptFirst = intent.getBooleanExtra("new mStaticPromptFirst", true);
                Log.i(TAG, "                  setting thePromptFirstSwitch.setChecked("+newStaticPromptFirst+")");
                Switch thePromptFirstSwitch = findViewById(R.id.thePromptFirstSwitch);
                thePromptFirstSwitch.setChecked(newStaticPromptFirst);
                Log.i(TAG, "                out onReceive: "+intent.getAction());
            } else if (intent.getAction().equals("service started")) {
                Log.i(TAG, "                in onReceive: "+intent.getAction());
                Switch theServiceSwitches[] = {
                  findViewById(R.id.theServiceSwitch),
                  findViewById(R.id.theServiceSwitch2),
                };
                for (Switch theServiceSwitch : theServiceSwitches) {
                  theServiceSwitch.setText("Service is on  ");
                  CHECK(!mSettingCheckedFromProgram);
                  mSettingCheckedFromProgram = true;
                  theServiceSwitch.setChecked(true);
                  CHECK(mSettingCheckedFromProgram);
                  mSettingCheckedFromProgram = false;
                  setSwitchTints(theServiceSwitch, /*thumbOn=*/true, /*trackOn=*/true);
                }
                Log.i(TAG, "                out onReceive: "+intent.getAction());
            } else if (intent.getAction().equals("service destroyed")) {
                Log.i(TAG, "                in onReceive: "+intent.getAction());
                Switch theServiceSwitches[] = {
                  findViewById(R.id.theServiceSwitch),
                  findViewById(R.id.theServiceSwitch2),
                };
                for (Switch theServiceSwitch : theServiceSwitches) {
                  theServiceSwitch.setText("Service is off ");
                  CHECK(!mSettingCheckedFromProgram);
                  mSettingCheckedFromProgram = true;
                  theServiceSwitch.setChecked(false);
                  CHECK(mSettingCheckedFromProgram);
                  mSettingCheckedFromProgram = false;
                  setSwitchTints(theServiceSwitch, /*thumbOn=*/false, /*trackOn=*/false);
                }
                Log.i(TAG, "                out onReceive: "+intent.getAction());
            } else {
                Log.i(TAG, "                in onReceive: "+intent.getAction());
                Log.i(TAG, "                  (unrecognized)");
                CHECK(false);  // shouldn't happen
                Log.i(TAG, "                out onReceive: "+intent.getAction());
            }
        }
    };  // mBroadcastReceiver

    private ContentObserver mAccelerometerRotationObserver = new ContentObserver(new Handler()) {
        // Per https://developer.android.com/reference/android/database/ContentObserver.html :
        // Delegate to ensure correct operation on older versions of the framework
        // that didn't have the onChange(boolean, Uri) method. (XXX do I need to worry about this? is framework runtime, or my compiletime?)
        @Override
        public void onChange(boolean selfChange) {
            Log.i(TAG, "            in TheActivity mAccelerometerRotationObserver onChange(selfChange="+selfChange+")");
            onChange(selfChange, null);
            Log.i(TAG, "            out TheActivity mAccelerometerRotationObserver onChange(selfChange="+selfChange+")");
        }
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            Log.i(TAG, "            in TheActivity mAccelerometerRotationObserver onChange(selfChange="+selfChange+", uri="+uri+")");
            try {
                Log.i(TAG, "              Settings.System.ACCELEROMETER_ROTATION: "+Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION));
            } catch (Settings.SettingNotFoundException e) {
                Log.i(TAG, "              Settings.System.ACCELEROMETER_ROTATION setting not found!?");
            }
            try {
                Log.i(TAG, "              (Settings.System.USER_ROTATION: "+TheService.surfaceRotationConstantToString(Settings.System.getInt(getContentResolver(), Settings.System.USER_ROTATION))+")");

            } catch (Settings.SettingNotFoundException e) {
                Log.i(TAG, "              (Settings.System.USER_ROTATION setting not found!?)");
            }
            updateAccelerometerRotationTextView();
            Log.i(TAG, "            out TheActivity mAccelerometerRotationObserver onChange(selfChange="+selfChange+", uri="+uri+")");
        }
    };  // mAccelerometerRotationObserver
    private ContentObserver mUserRotationObserver = new ContentObserver(new Handler()) {
        // Per https://developer.android.com/reference/android/database/ContentObserver.html :
        // Delegate to ensure correct operation on older versions of the framework
        // that didn't have the onChange(boolean, Uri) method. (XXX do I need to worry about this? is framework runtime, or my compiletime?)
        @Override
        public void onChange(boolean selfChange) {
            Log.i(TAG, "            in TheActivity mUserRotationObserver onChange(selfChange="+selfChange+")");
            onChange(selfChange, null);
            Log.i(TAG, "            out TheActivity mUserRotationObserver onChange(selfChange="+selfChange+")");
        }
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            Log.i(TAG, "            in TheActivity mUserRotationObserver onChange(selfChange="+selfChange+", uri="+uri+")");
            try {
                Log.i(TAG, "              (Settings.System.ACCELEROMETER_ROTATION: "+Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION)+")");
            } catch (Settings.SettingNotFoundException e) {
                Log.i(TAG, "              (Settings.System.ACCELEROMETER_ROTATION setting not found!?)");
            }
            try {
                Log.i(TAG, "              Settings.System.USER_ROTATION: "+TheService.surfaceRotationConstantToString(Settings.System.getInt(getContentResolver(), Settings.System.USER_ROTATION)));

            } catch (Settings.SettingNotFoundException e) {
                Log.i(TAG, "              Settings.System.USER_ROTATION setting not found!?");
            }
            updateUserRotationTextView();
            Log.i(TAG, "            out TheActivity mUserRotationObserver onChange(selfChange="+selfChange+", uri="+uri+")");
        }
    };  // mUserRotationObserver

    public TheActivity() {
        Log.i(TAG, "in TheActivity ctor");
        Log.i(TAG, "out TheActivity ctor");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "    in onCreate");
        Log.i(TAG, "      savedInstanceState = "+savedInstanceState);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        RelativeLayout theRelativeLayout = findViewById(R.id.theRelativeLayout);
        CHECK(theRelativeLayout != null); // was failing when there was an out of date layout-land/activity_main.xml
        Switch theServiceSwitches[] = {
          findViewById(R.id.theServiceSwitch),
          findViewById(R.id.theServiceSwitch2),
        };
        Button theAppSettingsButton = findViewById(R.id.theAppSettingsButton);
        Switch theWhackAMoleSwitch = findViewById(R.id.theWhackAMoleSwitch);
        Switch theAutoRotateSwitch = findViewById(R.id.theAutoRotateSwitch);
        Switch thePromptFirstSwitch = findViewById(R.id.thePromptFirstSwitch);
        Switch theOverrideSwitch = findViewById(R.id.theOverrideSwitch);
        Switch theRedSwitch = findViewById(R.id.theRedSwitch);
        ImageView theDialImageView1 = findViewById(R.id.theDialImageView1);
        ImageView theDialImageView = findViewById(R.id.theDialImageView);
        ImageView theDialImageView2 = findViewById(R.id.theDialImageView2);
        Switch theMonitorSwitch = findViewById(R.id.theMonitorSwitch);
        TextView thePolledValuesHeaderTextView = findViewById(R.id.thePolledValuesHeaderTextView);
        TextView thePolledStatusTextView = findViewById(R.id.thePolledStatusTextView);

        theWhackAMoleSwitch.setChecked(TheService.mStaticWhackAMole);
        theAutoRotateSwitch.setChecked(TheService.mStaticAutoRotate);
        thePromptFirstSwitch.setChecked(TheService.mStaticPromptFirst);
        theOverrideSwitch.setChecked(TheService.mStaticOverride);
        theRedSwitch.setChecked(TheService.getRed());
        theMonitorSwitch.setChecked(mPolling); // before listener installed; otherwise we'd get a callback immediately
        thePolledValuesHeaderTextView.setEnabled(mPolling);
        thePolledStatusTextView.setEnabled(mPolling);

        if (true) {
            // Replace theDialImageView1 with a MyImageView1
            int index = theRelativeLayout.indexOfChild(theDialImageView1);
            theRelativeLayout.removeView(theDialImageView1);

            theDialImageView1 = new MyImageView1(this);
            theDialImageView1.setImageResource(R.drawable.mygeomatic_rapporteur_5_svg_hi); // for size
            theDialImageView1.setRotation(90.f);
            theDialImageView1.setId(R.id.theDialImageView1);
            RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
              ViewGroup.LayoutParams.WRAP_CONTENT,
              ViewGroup.LayoutParams.WRAP_CONTENT);
            params.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
            theRelativeLayout.addView(theDialImageView1, index, params);
        }
        if (true) {
            // Replace theDialImageView2 with a MyImageView1
            int index = theRelativeLayout.indexOfChild(theDialImageView2);
            theRelativeLayout.removeView(theDialImageView2);

            theDialImageView2 = new MyImageView2(this);
            theDialImageView2.setImageResource(R.drawable.mygeomatic_rapporteur_5_svg_hi); // for size
            theDialImageView2.setRotation(90.f);
            theDialImageView2.setId(R.id.theDialImageView2);
            RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
              ViewGroup.LayoutParams.WRAP_CONTENT,
              ViewGroup.LayoutParams.WRAP_CONTENT);
            params.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
            theRelativeLayout.addView(theDialImageView2, index, params);
        }
        if (true) {
            // XXX not sure what I want here
            float scale = .79f;
            theDialImageView1.setScaleX(scale);
            theDialImageView1.setScaleY(scale);
            theDialImageView.setScaleX(scale);
            theDialImageView.setScaleY(scale);
            theDialImageView2.setScaleX(scale);
            theDialImageView2.setScaleY(scale);
        }

        if (true) {
            theWhackAMoleSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Log.i(TAG, "            in theWhackAMoleSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                    TheService.mStaticWhackAMole = isChecked;
                    if (isChecked) {
                        // TODO: do this through the service somehow?
                        try {
                          Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0);
                        } catch (SecurityException e) {
                            // XXX dup code
                            Log.i(TAG, "          Oh no, can't set system settings-- were permissions revoked?");
                            Toast.makeText(TheActivity.this, " Oh no, can't set system settings-- were permissions revoked?\nHere, please grant the permission.", Toast.LENGTH_SHORT).show();
                            Intent grantIntent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                            grantIntent.setData(Uri.parse("package:"+getPackageName()));
                            Log.i(TAG, "              grantIntent = "+grantIntent);
                            Log.i(TAG, "              calling startActivity with ACTION_MANAGE_WRITE_SETTINGS");
                            startActivity(grantIntent);
                            Log.i(TAG, "              returned from startActivity with ACTION_MANAGE_WRITE_SETTINGS");
                        }
                    }
                    Log.i(TAG, "            out theWhackAMoleSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                }
            });
        }
        if (true) {
            // I had a "force stop" button, but it's almost as easy to just let the user get to it through the "app settings" button, it's 2 clicks.  Force stop is probably hard to implement.
            // http://android.stackexchange.com/questions/33801/what-does-the-force-stop-button-mean#answer-48167
            theAppSettingsButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Log.i(TAG, "            in theAppSettingsButton onClick");
                    startActivityForResult(
                        new Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:"+getPackageName())),
                        0);
                    Log.i(TAG, "            out theAppSettingsButton onClick");
                }
            });
        }
        if (true) {
            theAutoRotateSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Log.i(TAG, "            in theAutoRotateSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                    TheService.mStaticAutoRotate = isChecked;
                    if (isChecked) {
                        // TODO: do this through the service somehow?
                        // TODO: need to catch SecurityException!
                        Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0);
                        // XXX just nuke everything without thinking too much.  TODO: think about it better
                        TheService.mStaticDegreesIsValid = false;
                        TheService.mStaticClosestCompassPoint = -1;
                    }
                    // TODO: make it update immediately?
                    Log.i(TAG, "            out theAutoRotateSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                }
            });
        }
        if (true) {
            thePromptFirstSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Log.i(TAG, "            in thePromptFirstSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                    TheService.mStaticPromptFirst = isChecked;
                    // No immediate effect; this setting just modifies the behavior of autorotate
                    Log.i(TAG, "            out thePromptFirstSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                }
            });
        }
        if (true) {
            theOverrideSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Log.i(TAG, "            in theOverrideSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                    TheService.setOverride(isChecked);
                    Log.i(TAG, "            out theOverrideSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                }
            });
        }
        if (true) {
            theRedSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Log.i(TAG, "            in theRedSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                    TheService.setRed(isChecked);
                    Log.i(TAG, "            out theRedSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                }
            });
        }
        if (true) {
            // Interesting, if we declare that we *don't* handle config changes,
            // then we get a checked-changed after onStart returns and before onResume is called, causing us to get back to the right state! Cool!
            theMonitorSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    Log.i(TAG, "            in theMonitorSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                    mPolling = isChecked;
                    TextView thePolledValuesHeaderTextView = findViewById(R.id.thePolledValuesHeaderTextView);
                    TextView thePolledStatusTextView = findViewById(R.id.thePolledStatusTextView);
                    thePolledValuesHeaderTextView.setEnabled(mPolling);
                    thePolledStatusTextView.setEnabled(mPolling);
                    Log.i(TAG, "                REMOVING POLLING CALLBACK IF ANY------------------------------------------------");
                    mPollingHandler.removeCallbacks(mPollingRunnable);  // ok if it wasn't scheduled
                    if (isChecked) {
                        // Presumably the activity is between onResume and onPause when this happens,
                        // so it's correct to start the periodic callback here.
                        // ARGH! Actually that's not true.  We also get here from inside onRestoreInstanceState(),
                        // which happens between onStart and onResume.
                        // There's something about this in https://developer.android.com/topic/libraries/architecture/lifecycle.html#onStop-and-savedState.
                        // We could use those facilities to query where we are in the lifecycle and only resume
                        // if in state RESUMED... but that seems like too much of a hassle.
                        // So for now, we just make sure to remove callbacks prior to every time we add them;
                        // that makes sure we don't add them twice.
                        Log.i(TAG, "                ADDING POLLING CALLBACK++++++++++++++++++++++++++++++++++++++++++++++++");
                        mPollingHandler.postDelayed(mPollingRunnable, 0);  // immediately
                    }
                    Log.i(TAG, "            out theMonitorSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                }
            });
        }

        if (true) {
            for (Switch theServiceSwitch : theServiceSwitches) {
                final Switch finalTheServiceSwitch = theServiceSwitch;
                theServiceSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(final CompoundButton buttonView, boolean isChecked) {
                        Log.i(TAG, "            in theServiceSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                        if (mSettingCheckedFromProgram) {
                            Log.i(TAG, "              (from program; not doing anything)");
                        } else {
                            if (isChecked) {
                                Log.i(TAG, "              calling startService");
                                setSwitchTints(finalTheServiceSwitch, /*thumbOn=*/false, /*trackOn=*/true);
                                startService(new Intent(TheActivity.this, TheService.class));
                                Log.i(TAG, "              returned from startService");
                                if (false) {
                                    // Make sure we can't mess up service's notion of whether it's running,
                                    // by sending a whole flurry of stuff.
                                    // NOTE: this does seem to confuse the notification icon! or, it did, before I started checking for mSettingCheckedFromProgram
                                    startService(new Intent(TheActivity.this, TheService.class));
                                    stopService(new Intent(TheActivity.this, TheService.class));
                                    stopService(new Intent(TheActivity.this, TheService.class));
                                    startService(new Intent(TheActivity.this, TheService.class));
                                    startService(new Intent(TheActivity.this, TheService.class));
                                }
                                if (false) { // too jumpy
                                  Log.i(TAG, "              setting text to \"Service is turning on  \"");
                                  buttonView.setText("Service is turning on  ");
                                }
                            } else {
                                Log.i(TAG, "              calling stopService");
                                setSwitchTints(finalTheServiceSwitch, /*thumbOn=*/true, /*trackOn=*/false);
                                stopService(new Intent(TheActivity.this, TheService.class));
                                Log.i(TAG, "              returned from stopService");
                                if (false) {
                                    // Make sure we can't mess up service's notion of whether it's running,
                                    // by sending a whole flurry of stuff
                                    // NOTE: this does seem to confuse the notification icon!
                                    stopService(new Intent(TheActivity.this, TheService.class));
                                    startService(new Intent(TheActivity.this, TheService.class));
                                    startService(new Intent(TheActivity.this, TheService.class));
                                    stopService(new Intent(TheActivity.this, TheService.class));
                                    stopService(new Intent(TheActivity.this, TheService.class));
                                }
                                if (false) { // too jumpy
                                  Log.i(TAG, "              setting text to \"Service is turning off  \"");
                                  buttonView.setText("Service is turning off  ");
                                }
                            }
                        }
                        Log.i(TAG, "            out theServiceSwitch onCheckedChanged(isChecked=" + isChecked + ")");
                    }
                });
            }
        }
        if (Build.VERSION.SDK_INT >=23) { // canWrite/canDrawOverlays don't exist earlier, and the dance isn't needed there anyway
          // Double-opt-in dance needed to write settings.
          // It causes the appropriate permissions screen to come up if it's wrong.
          // (Can also manually grant/ungrant by Settings -> Apps -> <this app> -> Modify system settings -> Yes/No, *if* activity is not running. (Force Stop first if it is))
          // Actually I think I can grant/ungrant on the fly, but the Settings switch gets out of sync with what it really is.  This is a reported bug, I think.
          if (!android.provider.Settings.System.canWrite(this)) {
              Intent grantIntent = new Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:"+getPackageName()));
              Log.i(TAG, "              grantIntent = "+grantIntent);
              Log.i(TAG, "              calling startActivity with ACTION_MANAGE_WRITE_SETTINGS");
              startActivity(grantIntent);
              Log.i(TAG, "              returned from startActivity with ACTION_MANAGE_WRITE_SETTINGS");
          } else {
              Log.i(TAG, "              can already modify system settings.  Cool.");
          }

          if (!android.provider.Settings.canDrawOverlays(this)) {
              Intent grantIntent = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:"+getPackageName()));
              Log.i(TAG, "              grantIntent = "+grantIntent);
              Log.i(TAG, "              calling startActivity with ACTION_MANAGE_OVERLAY_PERMISSION");
              startActivity(grantIntent); // XXX that example uses startActivityForResult. maybe use that when needed on the fly?
              Log.i(TAG, "              returned from startActivity with ACTION_MANAGE_OVERLAY_PERMISSION");
          } else {
              Log.i(TAG, "              can already draw overlays.  Cool.");
          }
        }

        CHECK(theRunningActivity == null); // XXX not confident in this
        theRunningActivity = this;

        Log.i(TAG, "    out onCreate");
    }

    private void updateAccelerometerOrientationDegreesTextView() {
        TextView theAccelerometerOrientationDegreesTextView = findViewById(R.id.theAccelerometerOrientationDegreesTextView);

        // Can I just query the current value of the accelerometer, without registering a changed-listener??

        // Q: how to get the android device's initial physical orientation when using OrientationEventListener?

        /*
        I'm using an OrientationEventListener to track the android device's physical orientation.
        But it seems I don't get any information about the orientation until the first onOrientationChanged event.
        If the device is lying still, that event might not happen for an arbitrarily long period of time.
        How do I determine the device's physical orientation prior to the first orientation change?
        Tags: android

        hmm, dup of: http://stackoverflow.com/questions/20131490/android-sensormanager-getorientation-before-event-triggered
        ah, it says take a look at getOrientationUsingGetRotationMatrix().

        Hmm that requires gravity and geomagnetic returned from sensorevents of sensors of type TYPE_ACCELEROMETER and TYPE_MAGNETIC_FIELD.
        And I have no idea how to use those.
        Do I just get one event of each and then unregister? or what?  this is frickin bizarre.
        Maybe look at source code for OrientationEventListener?
        http://alvinalexander.com/java/jwarehouse/android/core/java/android/view/OrientationEventListener.java.shtml
        Hmm, it's just translating ACCELEROMETER onSensorChanged events.  Hmm.

        I think there's a good example of how to really use these things here, if I want to revisit this:
          http://stackoverflow.com/questions/4819626/android-phone-orientation-overview-including-compass?rq=1#answer-6804786
        */

        if (false) { // XXX need to unconfuse what I'm doing here. does this have any value?
            if (TheService.mStaticDegreesIsValid) {
                theAccelerometerOrientationDegreesTextView.setText("  accelerometer degrees: "+TheService.mStaticDegrees);
            } else {
                theAccelerometerOrientationDegreesTextView.setText("  accelerometer degrees: ???");
            }
        }

        if (false) // XXX needs work, maybe just kill this
        {
            // http://stackoverflow.com/questions/20131490/android-sensormanager-getorientation-before-event-triggered#answer-20173273
            float R[] = new float[9];
            float I[] = new float[9];
            float gravity[] = new float[3]; // woops! need to initialize this using value returned by a SensorEvent of a Sensor of type TYPE_ACCELEROMETER
            float geomagnetic[] = new float[3]; // woops! need to initialize this using value returned by a SensorEvent of a Sensor of type TYPE_MAGNETIC_FIELD
            if (!android.hardware.SensorManager.getRotationMatrix(R,
                                                 I,
                                                 gravity,
                                                 geomagnetic)) {
                theAccelerometerOrientationDegreesTextView.setText("(SensorManager.getRotationMatrix failed)");
            } else {
                // https://developer.android.com/reference/android/hardware/SensorManager.html#jd-content
                float azimuthPitchRoll[] = new float[3];
                android.hardware.SensorManager.getOrientation(R, azimuthPitchRoll);
                theAccelerometerOrientationDegreesTextView.setText(
                  "R = "
                  +R[0]+" "+
                  +R[1]+" "+
                  +R[2]+" "+
                  +R[3]+" "+
                  +R[4]+" "+
                  +R[5]+" "+
                  +R[6]+" "+
                  +R[7]+" "+
                  +R[8]+" "+
                  "azimuth="+azimuthPitchRoll[0]+" pitch="+azimuthPitchRoll[1]+" roll="+azimuthPitchRoll[2]
                  );
            }
        }
    }

    private void updateAccelerometerRotationTextView() {
        TextView theAccelerometerRotationTextView = findViewById(R.id.theAccelerometerRotationTextView);
        try {
            int accelerometerRotation = Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION);
            theAccelerometerRotationTextView.setText("  Settings.System.ACCELEROMETER_ROTATION: "+accelerometerRotation);

        } catch (Settings.SettingNotFoundException e) {
            theAccelerometerRotationTextView.setText("  Settings.System.ACCELEROMETER_ROTATION setting not found!?");
        }
    }
    private void updateUserRotationTextView() {
        TextView theUserRotationTextView = findViewById(R.id.theUserRotationTextView);
        try {
            int userRotation = Settings.System.getInt(getContentResolver(), Settings.System.USER_ROTATION);
            theUserRotationTextView.setText("  Settings.System.USER_ROTATION: "+TheService.surfaceRotationConstantToString(userRotation));

        } catch (Settings.SettingNotFoundException e) {
            theUserRotationTextView.setText("  Settings.System.USER_ROTATION setting not found!?");
        }
    }

    // set the text view to mMostRecentConfigurationOrientation.
    private void updateConfigurationOrientationTextView() {
        TextView theConfigurationOrientationTextView = findViewById(R.id.theConfigurationOrientationTextView);
        // XXX this is a lie the first time-- maybe make that a parameter
        theConfigurationOrientationTextView.setText("  onConfigurationChanged newConfig.orientation = " + TheService.orientationConstantToString(mMostRecentConfigurationOrientation));
    }

    private void updatePolledStatusTextView() {
        TextView thePolledStatusTextView = findViewById(R.id.thePolledStatusTextView);
        int accelerometerRotation = -1;
        boolean gotAccelerometerRotation = false;
        int userRotation = -1;
        boolean gotUserRotation = false;
        try {
            accelerometerRotation = Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION);
            gotAccelerometerRotation = true;
        } catch (Settings.SettingNotFoundException e) {}
        try {
            userRotation = Settings.System.getInt(getContentResolver(), Settings.System.USER_ROTATION);
            gotUserRotation = true;
        } catch (Settings.SettingNotFoundException e) {}
        String message = "";
        message += ("  getRequestedOrientation() = " + TheService.screenOrientationConstantToString(getRequestedOrientation()));
        message += "\n";
        message += (gotAccelerometerRotation ? "  Settings.System.ACCELEROMETER_ROTATION: "+accelerometerRotation : "[no Settings.System.ACCELEROMETER_ROTATION]");
        message += "\n";
        message += (gotUserRotation ? "  Settings.System.USER_ROTATION: "+TheService.surfaceRotationConstantToString(userRotation) : "[no Settings.System.USER_ROTATION]");
        message += "\n";
        // XXX assert it's equal to mMostRecentConfigurationOrientation?  not sure, they might be slightly out of sync temporarily
        message += ("  getResources().getConfiguration().orientation = " + TheService.orientationConstantToString(getResources().getConfiguration().orientation));
        message += "\n";
        message += ("  getWindowManager().getDefaultDisplay().getRotation() = " + TheService.surfaceRotationConstantToString(getWindowManager().getDefaultDisplay().getRotation()));
        thePolledStatusTextView.setText(message);

        TextView thePolledValuesHeader = findViewById(R.id.thePolledValuesHeaderTextView);
        mNumUpdates++;
        thePolledValuesHeader.setText("Polled values:  ("+mNumUpdates+" update"+(mNumUpdates==1?"":"s")+")");
    }

    @Override
    protected void onStart() {
        Log.i(TAG, "        in onStart");
        super.onStart();
        Switch theServiceSwitches[] = {
          findViewById(R.id.theServiceSwitch),
          findViewById(R.id.theServiceSwitch2),
        };
        boolean serviceIsRunning = TheService.isServiceRunning();
        Log.i(TAG, "          calling theServiceSwitch.setChecked("+serviceIsRunning+")");
        for (Switch theServiceSwitch : theServiceSwitches) {
            CHECK(!mSettingCheckedFromProgram);
            mSettingCheckedFromProgram = true;
            theServiceSwitch.setChecked(serviceIsRunning);
            CHECK(mSettingCheckedFromProgram);
            mSettingCheckedFromProgram = false;
            setSwitchTints(theServiceSwitch, /*thumbOn=*/serviceIsRunning, /*trackOn=*/serviceIsRunning);
        }
        Log.i(TAG, "          returned from theServiceSwitch.setChecked("+serviceIsRunning+")");
        // That invoked the listener which set the label to "Service is on" or "Service is off";
        // overwrite it with something that says "initially".
        if (serviceIsRunning) {
            Log.i(TAG, "          setting text to \"Service is initially on  \"");
            for (Switch theServiceSwitch : theServiceSwitches) {
              theServiceSwitch.setText("Service is initially on  ");
            }
        } else {
            Log.i(TAG, "          setting text to \"Service is initially off \"");
            for (Switch theServiceSwitch : theServiceSwitches) {
              theServiceSwitch.setText("Service is initially off  ");
            }
        }
        Log.i(TAG, "        out onStart");
    }

    // called only when there's a saved instance previously saved using onSaveInstanceState().
    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        Log.i(TAG, "          in onRestoreInstanceState");  // shoehorned this in so indent is intermediate
        super.onRestoreInstanceState(savedInstanceState);  // restores the state of the view hierarchy
        Log.i(TAG, "          out onRestoreInstanceState");  // shoehorned this in so indent is intermediate
    }

    @Override
    protected void onResume() {
        Log.i(TAG, "            in onResume");
        super.onResume();

        // see https://stackoverflow.com/questions/7887169/android-when-to-register-unregister-broadcast-receivers-created-in-an-activity for discussion of whether to do this in onStart/onStop or onPause/onResume . still not entirely clear
        {
            android.support.v4.content.LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, new IntentFilter("degrees changed") {{
                addAction("mStaticPromptFirst changed");
                addAction("mStaticClosestCompassPoint changed");
                addAction("service started");
                addAction("service destroyed");
            }});
        }

        {
            android.net.Uri uri = Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION);
            Log.i(TAG, "      uri = "+uri);
            getContentResolver().registerContentObserver(uri, false, mAccelerometerRotationObserver);
        }

        {
            android.net.Uri uri = Settings.System.getUriFor(Settings.System.USER_ROTATION);
            Log.i(TAG, "      uri = "+uri);
            getContentResolver().registerContentObserver(uri, false, mUserRotationObserver);
        }

        {
          if (mPolling) {
              // Yes, have to remove them, since they might have got added during onRestoreInstanceState.
              // (another option would be to query whether the callback is installed, and/or keep track
              // of whether it's installed).
              Log.i(TAG, "                REMOVING POLLING CALLBACK IF ANY------------------------------------------------");
              mPollingHandler.removeCallbacks(mPollingRunnable);  // ok if it wasn't scheduled
              Log.i(TAG, "                ADDING POLLING CALLBACK++++++++++++++++++++++++++++++++++++++++++++++++");
              mPollingHandler.postDelayed(mPollingRunnable, 0);  // immediately
          }
        }

        mMostRecentConfigurationOrientation = getResources().getConfiguration().orientation;

        updateAccelerometerOrientationDegreesTextView();
        updateAccelerometerRotationTextView();
        updateUserRotationTextView();
        updateConfigurationOrientationTextView();
        updatePolledStatusTextView();
        Log.i(TAG, "            out onResume");
    }

    @Override
    protected void onPause() {
        Log.i(TAG, "            in onPause");

        android.support.v4.content.LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver);

        getContentResolver().unregisterContentObserver(mAccelerometerRotationObserver); // ok if it wasn't registered, I think... although it should be
        getContentResolver().unregisterContentObserver(mUserRotationObserver); // ok if it wasn't registered, I think... although it should be
        Log.i(TAG, "                REMOVING POLLING CALLBACK IF ANY------------------------------------------------");
        mPollingHandler.removeCallbacks(mPollingRunnable); // ok if it wasn't scheduled

        super.onPause();
        Log.i(TAG, "            out onPause");
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        Log.i(TAG, "          in onSaveInstanceState");  // shoehorned this in so indent is intermediate
        super.onSaveInstanceState(outState);
        Log.i(TAG, "          out onSaveInstanceState");  // shoehorned this in so indent is intermediate
    }

    @Override
    protected void onStop() {
        Log.i(TAG, "        in onStop");
        super.onStop();
        Log.i(TAG, "        out onStop");
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "    in onDestroy");
        super.onDestroy();
        CHECK(theRunningActivity == this); // XXX not confident in this
        if (theRunningActivity == this) theRunningActivity = null;
        Log.i(TAG, "    out onDestroy");
    }

    // Note, in order for this to be called (rather than the system stopping and restarting
    // the activity), the manifest must have "orientation|screenSize" in android:configChanges
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        Log.i(TAG, "in onConfigurationChanged");
        super.onConfigurationChanged(newConfig);
        Log.i(TAG, "  newConfig = "+newConfig);
        Log.i(TAG, "  newConfig.orientation = "+TheService.orientationConstantToString(newConfig.orientation));

        mMostRecentConfigurationOrientation = newConfig.orientation;
        updateConfigurationOrientationTextView();

        {
            Switch theOverrideSwitch = findViewById(R.id.theOverrideSwitch);
            Switch theRedSwitch = findViewById(R.id.theRedSwitch);
            RelativeLayout.LayoutParams redSwitchLayoutParams = ((RelativeLayout.LayoutParams)theRedSwitch.getLayoutParams());
            if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
                // put red switch below override switch, right-aligned with it
                Log.i(TAG, "  putting red switch below and right-aligned with override switch");
                redSwitchLayoutParams.addRule(RelativeLayout.RIGHT_OF, 0); // i.e. removeRule, but works with runtime<17
                redSwitchLayoutParams.addRule(RelativeLayout.END_OF, 0); // i.e. removeRule, but works with runtime<17
                redSwitchLayoutParams.addRule(RelativeLayout.ALIGN_TOP, 0); // i.e. removeRule, but works with runtime<17
                redSwitchLayoutParams.addRule(RelativeLayout.BELOW, theOverrideSwitch.getId());
                redSwitchLayoutParams.addRule(RelativeLayout.ALIGN_RIGHT, theOverrideSwitch.getId());
                redSwitchLayoutParams.addRule(RelativeLayout.ALIGN_END, theOverrideSwitch.getId());
                //layout_below = theOverrideSwitch;
                //theRedSwitch.alignRight = theOverrideSwitch;
            } else if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                // TODO: want this to happen *every* time we find ourselves in landscape mode!
                //    - in onConfigurationChanged, if we're handling config changes (works because of this code here)
                //    - if we start in landscape mode (doesn't work)
                //    - if we're not handling config changes (doesn't work)
                // put red switch to right of override switch, top-aligned with it
                //theRedSwitch.layout_toRightOf = theOverrideSwitch;
                //theRedSwitch.alignTop = theOverrideSwitch;
                Log.i(TAG, "  putting red switch to right and top-aligned with override switch");
                redSwitchLayoutParams.addRule(RelativeLayout.BELOW, 0);  // i.e. removeRule, but works with runtime<17
                redSwitchLayoutParams.addRule(RelativeLayout.ALIGN_RIGHT, 0);  // i.e. removeRule, but works with runtime<17
                redSwitchLayoutParams.addRule(RelativeLayout.ALIGN_END, 0);  // i.e. removeRule, but works with runtime<17
                redSwitchLayoutParams.addRule(RelativeLayout.RIGHT_OF, theOverrideSwitch.getId());
                redSwitchLayoutParams.addRule(RelativeLayout.END_OF, theOverrideSwitch.getId());
                redSwitchLayoutParams.addRule(RelativeLayout.ALIGN_TOP, theOverrideSwitch.getId());
            }
        }

        Log.i(TAG, "out onConfigurationChanged");
    }

    // Functions to make switches show "turning on", "turning off". Asked on stack overflow: https://stackoverflow.com/questions/47469451/how-to-make-an-android-switch-control-show-that-its-turning-on-or-turning-of?noredirect=1#comment81893491_47469451
    // use "off" colors for both off and on position
    private static void setSwitchTints(Switch swtch,
                                      boolean thumbOn, // use "on" color for thumb? (for both on and off position)
                                      boolean trackOn) { // use "on" color for track? (for both on and off position)
        int[][] states = {
            {-android.R.attr.state_checked},
            {android.R.attr.state_checked},
        };

        // ???,???,??? -> 236,236,236
        // 236,236,236 -> 231,231,231
        // 239,239,239 -> 234,234,234
        // 240,240,240 -> 235,235,235
        // 241,241,241 -> 233,233,233
        // 242,242,242 -> 237,237,237
        // 243,243,243 -> 238,238,238
        // 244,244,244 -> 239,239,239
        // wtf: not monotonic??  want 236, but can't get it exactly??
        int thumbColorOff = Color.rgb(240,240,240);  // <--- hmm, 241,241,241 = f1f1f1 comes out in some intermediate file as switch_thumb_normal_material_light ??

        // 255,64,129 -> 250,23,126
        int thumbColorOn = Color.rgb(255,64,129); // <--- AH HA!  THIS is exactly the value I see in Tools -> Android -> Theme Editor, for @color/colorAccent !  and that;s this in app/src/main/res/values/colors.xml: <color name="colorAccent">#FF4081</color>
                        // hmm, see this: https://stackoverflow.com/questions/11253512/change-on-color-of-a-switch#comment-57432698
                        // and see this: https://android.jlelse.eu/customizing-switch-using-xml-ca0d37204a86
                        // note, that file contains:
                        /*
	                  <color name="colorPrimary">#3F51B5</color>    63,81,181
	                  <color name="colorPrimaryDark">#303F9F</color> 48,63,159
	                  <color name="colorAccent">#FF4081</color>     255,64,129
                        */

        // 0,0,0       -> 185,185,185
        int trackColorOff = Color.rgb(0,0,0);

        // 255,65,130  -> 251,202,219    (may be the colorAccent color does it too, though)
        int trackColorOn = Color.rgb(255,65,130);

        int[] thumbColors = {
          thumbOn ? thumbColorOn : thumbColorOff,
          thumbOn ? thumbColorOn : thumbColorOff,
        };
        int[] trackColors = {
          trackOn ? trackColorOn : trackColorOff,
          trackOn ? trackColorOn : trackColorOff,
        };
        if (Build.VERSION.SDK_INT >=23) { // (runtime) otherwise these methods don't exist
          swtch.setThumbTintList(new ColorStateList(states, thumbColors));
          swtch.setTrackTintList(new ColorStateList(states, trackColors));
        }
    }
}
