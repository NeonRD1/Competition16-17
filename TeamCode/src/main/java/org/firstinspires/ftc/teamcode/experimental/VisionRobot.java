package org.firstinspires.ftc.teamcode.experimental;

import com.qualcomm.robotcore.hardware.HardwareMap;
import com.qualcomm.robotcore.util.Range;

import org.lasarobotics.vision.android.Cameras;
import org.lasarobotics.vision.ftc.resq.Beacon;
import org.lasarobotics.vision.ftc.resq.Constants;
import org.lasarobotics.vision.opmode.VisionOpMode;
import org.lasarobotics.vision.opmode.extensions.CameraControlExtension;
import org.lasarobotics.vision.util.ScreenOrientation;
import org.opencv.core.Size;

import java.util.LinkedList;
import java.util.List;

/**
 * Created by gssmrobotics on 2/20/2017.
 */

public class VisionRobot extends Robot {
    private State state = State.NULL; //state of robot
    private VisionOpMode opMode = null;
    private long lastStageTime = 0;
    private final State[] busyStates = {State.PD_BEACON, State.TIME_DRIVE}; //TODO add more

    //PDtoBeacon variables
        private double beaconConfidence = 0.1; //TODO real value or set in init
        private double initialBeaconConfidence = 0.2; //TODO real value
        private int slidingConfidencePeriod = 5; //TODO real value
        private int frameSizeBuffer = 0; //TODO find good value and figure out scale issues

        private LinkedList<Double> slidingConfidence = null; //TODO initialize
        private boolean startedToBeacon1 = false;
        private double prevError=0;
        private double prevTime=0;
        private int leftRed=0, rightRed=0;

    //PDtoBeacon cached variables
        private double Kp=0, Kd=0, Ki=0;
        private double maxTime=0;

    //timeDrive cached variables
        private double leftPower=0, rightPower=0;
        private long time=0;

    /**
     * Constructor for VisionRobot - extension of Robot class with vision
     *
     * @param map    HardwareMap for getting motor control
     * @param opMode VisionOpMode for vision stuff (probably just pass 'self')
     */
    public VisionRobot(HardwareMap map, VisionOpMode opMode) {
        super(map);
        this.opMode = opMode;
        //this.init(); //No no. Causes errors.

        //PDtoBeacon stuff
        slidingConfidence = new LinkedList<>();
    }

    public boolean contains(State[] list, State item) {
        for(State i : list) {
            if(item == i) return true;
        }
        return false;
    }

    /**
     * Current state or state of previous action
     */
    public enum State {
        PD_BEACON, //Robot is in the middle of PD
        TIME_DRIVE, //Robot is in middle of timeDrive method
        SUCCESS, //Last action was successful
        FAILURE_TECH, //Failure for technical reasons (i.e. beacon navigation lost sight of beacon)
        FAILURE_TIMEOUT, //Robot timed out on previous task
        CANCELLED, //Previous action was cancelled
        NULL //What it starts out as -> means absolutely nothing
    }

    private void setState(State state) {
        this.state = state;
        lastStageTime = System.currentTimeMillis();
    }

    /**
     * Alliance enum for use in hitting techniques
     */
    public enum Alliance {
        RED, BLUE
    }

    /**
     * Initialize vision stuff (Except enableExtension() calls)
     *
     * The following must be in opmode initialization before this call:
     * """
     * enableExtension(Extensions.BEACON);         //Beacon detection
     * enableExtension(Extensions.ROTATION);       //Automatic screen rotation correction
     * enableExtension(Extensions.CAMERA_CONTROL); //Manual camera control
     * """
     */
    public void init() {

        //Set primary camera to main camera on back
        opMode.setCamera(Cameras.PRIMARY);

        //Set frame size
        opMode.setFrameSize(new Size(900, 900));

        opMode.beacon.setAnalysisMethod(Beacon.AnalysisMethod.FAST);

        //set color tolerance
        //TODO play around with tolerance values
        opMode.beacon.setColorToleranceRed(0);
        opMode.beacon.setColorToleranceBlue(0);

        //camera control stuff
        opMode.rotation.setIsUsingSecondaryCamera(false);
        opMode.rotation.disableAutoRotate();
        opMode.rotation.setActivityOrientationFixed(ScreenOrientation.PORTRAIT);

        //camera control extension specifications
        opMode.cameraControl.setColorTemperature(CameraControlExtension.ColorTemperature.AUTO);
        opMode.cameraControl.setManualExposureCompensation(Constants.EXPO_COMP);
    }

    /**
     * Is the robot busy? (Descriptive commenting thanks to FIRST API)
     * @return Returns true if the robot is busy
     */
    public boolean isBusy() {
        return contains(busyStates, state);
    } //TODO fix this to use others

    public State getState() {
        return state;
    }

    /**
     * Cancels any action currently going on (mostly for emergencies)
     */
    public void cancel() {
        leftMotor.setPower(0);
        rightMotor.setPower(0);
        state = State.CANCELLED;
        //TODO set everything to defaults
    }

    /**
     * navigates to beacon (until beacon goes out of camera view)
     *
     * @param Kp      proportional constant
     * @param Kd      differential constant
     * @param maxTime max time to go before quitting millis
     */
    public void PDtoBeacon(double Kp, double Kd, double maxTime) {
        if(!isBusy()) { //TODO TODO TODO fix this
            setState(State.PD_BEACON);
            this.Kp = Kp;
            this.Kd = Kd;
            this.maxTime = maxTime;
            slidingConfidence = new LinkedList<>();
            prevError = 0; //TODO make this better so no big initial jump
            prevTime = 0; //TODO see above
            leftRed = 0;
            rightRed = 0;
        }
        if(state == State.PD_BEACON) {
            Beacon.BeaconAnalysis anal = opMode.beacon.getAnalysis();
            Size frameSize = opMode.getFrameSize();

            opMode.telemetry.addData("Confidence", anal.getConfidenceString());

            if (anal.isBeaconFound() && anal.getConfidence() > beaconConfidence) {
                double beaconHeight = anal.getHeight();
                double beaconWidth = anal.getWidth();
                double frameHeight = frameSize.height;
                double frameWidth = frameSize.width;
                if (frameHeight - beaconHeight >= frameSizeBuffer
                        || frameWidth - beaconWidth >= frameSizeBuffer) {
                    state = State.SUCCESS;
                }

                //TODO use telemetry to ensure that frame size and beacon size are the same scale

                double beaconCenterX = anal.getCenter().x; //beacon center x
                double frameCenterX = frameSize.width / 2; //frame center x
                double error = frameCenterX - beaconCenterX; //error in x from beacon (right is positive)

                double time = System.currentTimeMillis();

                if (startedToBeacon1) {
                    double diff = (error - prevError) / (time - prevTime); //error differential
                    double steering = Kp * error + Kd * diff; //PD steering uses error and diff times constants
                    setLeftPower(Range.clip(1 + (steering < 0 ? steering : 0), 0, 1)); //brake left if steering less than zero; clipped [0,1]
                    setRightPower(Range.clip(1 + (steering > 0 ? steering : 0), 0, 1)); //brake right if steering greater than zero; clipped [0,1]
                    prevError = error;
                    prevTime = time;

                    opMode.telemetry.addData("Proportional Error", error);
                    opMode.telemetry.addData("Differential Error", diff);
                    opMode.telemetry.addData("Steering", steering);
                } else {
                    prevError = error; //If this is the first time, only get the error to prevError
                    prevTime = time;
                    startedToBeacon1 = true;
                }

                if (anal.isRightRed()) {
                    rightRed++; //Add to right count if right is red
                }

                if (anal.isLeftRed()) {
                    leftRed++; //Add to left count if left is red
                }
            } else {
                //TODO if beacon not found (this is temporary and is stopping not ideal)
                brake();
            }
        }
    }

    /**
     * Calculates error of beacon position for use in PD to beacon based on beacon position if found
     * @return Returns error of beacon (left negative, right positive)
     */
    private double error() {
        return 0; //TODO calculate error and call this from PDtoBeacon
    }

    /**
     * Sets beacon confidence
     *
     * @param beaconConfidence Minimum confidence which must be maintained to continue navigation
     */
    public void setBeaconConfidence(double beaconConfidence) {
        this.beaconConfidence = beaconConfidence;
    }

    /**
     * Sets initial beacon confidence
     *
     * @param initialBeaconConfidence Minimum confidence which must be maintained to start navigation
     */
    public void setInitialBeaconConfidence(double initialBeaconConfidence) {
        this.initialBeaconConfidence = initialBeaconConfidence;
    }

    /**
     * Drives until beacon is found or failure due to timeout
     *
     * @param leftPower  Power of left side (with camera end as front)
     * @param rightPower Power of right side (with camera end as front)
     */
    public void detectBeacon(double leftPower, double rightPower, long time) {
        //TODO
    }

    /**
     * Drive a certain amount of time with constant power
     *
     * @param leftPower  Left drive power (with camera end as front)
     * @param rightPower Right drive power (with camera end as front)
     * @param time
     */
    public void timeDrive(double leftPower, double rightPower, long time) {
        if(!isBusy()) {
            setState(State.TIME_DRIVE); //Set state to start going with this op
            this.leftPower = leftPower;
            this.rightPower = rightPower;
            this.time = time;

            //initialize motors
            this.setLeftPower(leftPower); //TODO check that these are in the same direction
            this.setRightPower(rightPower);
        }
        if(state == State.TIME_DRIVE) {
            if(System.currentTimeMillis() >= lastStageTime + time) {
                //Time's up - it's done driving
                cancel(); //call one function to stop everything instead of doing it myself
                setState(State.SUCCESS);
            }
            //continue driving
        }
        //TODO can we just disregard bad ops?
    }

    /**
     * Gets beacon color (in nicely packaged object from lasarobotics)
     *
     * @return Returns BeaconColor object for raw use (will be used a lot later)
     */
    public Beacon.BeaconColor beaconColor() {
        //TODO (and decide if this is what we want)
        return null;
    }

    /**
     * Hits beacon from close up (should be called after PDtoBeacon)
     *
     * @param leftPower  Left motor power (with camera as front)
     * @param rightPower Right motor power (with camera as front)
     * @param maxTime    Max time to drive before failure due to timeout
     */
    public void hitBeacon(double leftPower, double rightPower, long maxTime) {
        //TODO
    }

    /**
     * Backs up from beacon to
     *
     * @param leftPower  Left motor power (with camera as front)
     * @param rightPower Right motor power (with camera as front)
     * @param time       Time to drive before stopping
     */
    public void backupFromBeacon(double leftPower, double rightPower, long time) {
        //TODO
    }

    /**
     * Determines if the beacon needs to be clicked
     *
     * @param alliance Alliance color
     */
    public void needToClick(Alliance alliance) {
        //TODO
        //TODO if beacon confidence is below threshold possibly tech failure but still return
    }

    /**
     * Continues whatever action is in progress (whatever the state of the robot is)
     */
    public void continueAction() {
        switch (state) {
            case TIME_DRIVE:
                timeDrive(leftPower, rightPower, time);
                break;
        }
    }

    /**
     * Logs a ton of data to telemetry (i.e. beacon location details)
     */
    public void logData() {
        //TODO
        opMode.telemetry.addData("VisionRobotStatus", "Thoroughly incomplete");
    }
}
