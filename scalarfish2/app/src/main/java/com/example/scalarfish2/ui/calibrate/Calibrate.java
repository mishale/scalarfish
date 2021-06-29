package com.example.scalarfish2.ui.calibrate;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.example.scalarfish2.R;
import com.example.scalarfish2.databinding.FragmentHomeBinding;
import com.example.scalarfish2.ui.verify.Verify;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.JavaCameraView;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.core.MatOfPoint2f;
import org.opencv.imgproc.Imgproc;
import org.opencv.calib3d.*;
import org.opencv.core.MatOfPoint3f;
import org.opencv.core.Point3;

public class Calibrate extends Fragment implements View.OnClickListener, CameraBridgeViewBase.CvCameraViewListener2 {
    private static final int MY_CAMERA_REQUEST_CODE = 100;

    // For image capturing
    static final int REQUEST_IMAGE_CAPTURE = 1;

    // For accessing the elements in the fragment
    ImageView imgView; /* This is not needed, for now, since we are live capturing the images */
    View view; /* The view everything is in */
    Button btnCaptureImg; /* button to start the image capture process with */
    Button btnCalibrate; /* button to start the calibration process with */
    Button btnVerify; /* button that takes the user to the verification fragment */
    ImageButton btnCaptureCalibImg; /* button for taking single pictures */
    TextView txtImgCounter; /* text counter for valid images taken */
    ProgressBar loadingSpinner; /* circular spinner, displaying calculation */

    // For creating a path to save the image to, not needed for now
    String currentPhotoPath;
    Uri photoURI;
    File photoFile;

    // OpenCV camera
    private JavaCameraView javaCameraView; /* the camera we are going to use instead of the android camera */
    private Mat mRGBA; /* a matrix for copying the values of the current frame of the camera to */
    private final int PERMISSIONS_READ_CAMERA=1;
    int cameraCounter = 0; /* for counting and reducing fps */

    // Calibration values
    Size boardSize = new Size(9,6); /* The size of the chessboard */
    MatOfPoint2f imageCorners = new MatOfPoint2f(); /* A matrix for detecting the corners */
    MatOfPoint2f imageCornerCopy = new MatOfPoint2f();
    MatOfPoint3f obj = new MatOfPoint3f(); /* 3d matrix */

    List<Mat> imagePoints = new ArrayList<>(); /* A list of matrices for saving the image corners of every captured chessboard */
    List<Mat> objectPoints = new ArrayList<>(); /* List of 3d matrices */

    Mat intrinsic = new Mat(3, 3, CvType.CV_32FC1); /* Intrinsic camera values? */
    Mat distCoeffs = new Mat(); /* The final matrix for undistorting images */
    Mat savedImage = new Mat(); /* saving a captured image from the camera for setting the image dimensions when calibrating */

    boolean calibrated;
    boolean calibInProgress = false;
    boolean debug = true;

    int imgCounter = 0; /* Counts how many valid calibration images have been taken already */

    private FragmentHomeBinding binding;

    // For enabling the camera view
    BaseLoaderCallback baseLoaderCallback = new BaseLoaderCallback(getContext()) {
        @Override
        public void onManagerConnected(int status) {
            Log.d("Callback", "callbacksuccess");
            switch (status)
            {
                case BaseLoaderCallback.SUCCESS:
                {
                    Log.d("Callback", "case success");
                    javaCameraView.enableView();
                    break;
                }
                default:
                {
                    Log.d("Callback", "case default");
                    super.onManagerConnected(status);
                    break;
                }

            }
        }
    };

    public Calibrate() {
        // Required empty public constructor
    }

    public static Calibrate newInstance(String param1, String param2) {
        Calibrate fragment = new Calibrate();
        Bundle args = new Bundle();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
        }

        // Calculate the number of squares on the board
        int numSquares = (int)boardSize.height * (int)boardSize.width;
        for(int j = 0; j < numSquares; j++) {
            obj.push_back(new MatOfPoint3f(new Point3(j / (int)boardSize.width, j % (int)boardSize.height, 0.0f)));
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        view = inflater.inflate(R.layout.fragment_calibrate, container, false);

        // When loading the fragment, no matter from where, change the title
        ((AppCompatActivity)getActivity()).getSupportActionBar().setTitle("Calibration");

        // Get the button for capturing a calibration image and set the listener
        btnCaptureImg = (Button) view.findViewById(R.id.btnCaptureImg);
        btnCaptureImg.setOnClickListener(this);

        // Get the button for calibrating and do the same
        btnCalibrate = (Button) view.findViewById(R.id.btnCalibrate);
        btnCalibrate.setOnClickListener(this);

        // Button for going to the calibration verification fragment
        btnVerify = (Button) view.findViewById(R.id.btnVerify);
        btnVerify.setOnClickListener(this);
        btnVerify.setVisibility(View.INVISIBLE);

        // Button for taking single pictures for calibration
        btnCaptureCalibImg = (ImageButton) view.findViewById(R.id.btnCaptureCalibImg);
        btnCaptureCalibImg.setOnClickListener(this);
        btnCaptureCalibImg.setVisibility(View.INVISIBLE);

        // Get the progress bar
        loadingSpinner = (ProgressBar) view.findViewById(R.id.progressBar);
        loadingSpinner.setVisibility(View.INVISIBLE);

        txtImgCounter = (TextView) view.findViewById(R.id.txtCalibCounter);

        // Get the OpenCV camera view in the fragment's layout
        javaCameraView = (JavaCameraView) view.findViewById(R.id.openCvCameraView);
        javaCameraView.setCvCameraViewListener(this);
        // Set the front camera to the one that will be used
        javaCameraView.setCameraIndex(CameraBridgeViewBase.CAMERA_ID_BACK);


        //javaCameraView.enableFpsMeter();

        // Get the image view to display the captured image on
        // This is not used at the moment
        imgView = (ImageView) view.findViewById(R.id.imgViewCalibration);

        // Permission check for camera
        if (ContextCompat.checkSelfPermission(getContext(),
                Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            // Permission is not granted
            // Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(getActivity(),
                    Manifest.permission.CAMERA)) {
                // Show an explanation to the user *asynchronously* -- don't block
                // this thread waiting for the user's response! After the user
                // sees the explanation, try again to request the permission.
            } else {
                // No explanation needed, we can request the permission.
                ActivityCompat.requestPermissions(getActivity(),
                        new String[]{Manifest.permission.CAMERA},
                        PERMISSIONS_READ_CAMERA);
                // MY_PERMISSIONS_REQUEST_READ_CONTACTS is an
                // app-defined int constant. The callback method gets the
                // result of the request.
            }
        } else {
            Log.d("Permissions", "Permissions granted");
            javaCameraView.setCameraPermissionGranted();
            // Permission has already been granted
        }
        return view;
    }

    @Override
    public void onClick(View v) {
        switch(v.getId()) {
            case R.id.btnCaptureImg:
                // Make the OpenCV camera view visible when the button is pressed
                javaCameraView.setVisibility(SurfaceView.VISIBLE);
                // Disable the button
                btnCaptureImg.setVisibility(SurfaceView.INVISIBLE);
                // Enable the button to take images
                btnCaptureCalibImg.setVisibility(View.VISIBLE);
                break;
            case R.id.btnCalibrate:
                // Make the loading spinner visible
                loadingSpinner.setVisibility(View.VISIBLE);
                btnCalibrate.setVisibility(View.INVISIBLE);
                calibInProgress = true;
                calibrateCamera();
                break;
            case R.id.btnVerify:
                Fragment fragment = new Verify();
                FragmentTransaction transaction = getFragmentManager().beginTransaction();
                transaction.replace(R.id.nav_host_fragment_content_main, fragment);
                transaction.addToBackStack(null);
                transaction.commit();
                break;
            case R.id.btnCaptureCalibImg:
                checkImageForChessboard();
                break;
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {

        // Ensure that this result is for the camera permission request
        if (requestCode == PERMISSIONS_READ_CAMERA) {
            // Check if the request was granted or denied
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // The request was granted -> tell the camera view
                javaCameraView.setCameraPermissionGranted();
            } else {
                // The request was denied -> tell the user and exit the application
                Log.d("Permission", "Permission was denied");
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    // Creates an unique image file name
    // Not sure if this is still required, since we capture images from the live view without saving them first
    // The only thing that needs to be saved in this fragment is the distortion matrix
    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        // getActivity() is required for calling the function, since this is a fragment
        File storageDir = getActivity().getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File image = File.createTempFile(
                imageFileName,  /* prefix */
                ".jpg",   /* suffix */
                storageDir      /* directory */
        );

        currentPhotoPath = image.getAbsolutePath();
        return image;
    }

    // Open the camera, take a picture and save the picture
    // This uses intents to open the android camera, this is no longer necessary since we use the opencv camera
    private void dispatchTakePictureIntent() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);

        if (takePictureIntent.resolveActivity(getActivity().getPackageManager()) != null) {
            photoFile = null;
            try {
                photoFile = createImageFile();
                Log.i("File created", "Image file created: " + photoFile.toString());
            } catch (IOException ex) {
                // Error while creating file
                Log.e("File creation error", ex.toString());
            }

            if (photoFile != null) {
                photoURI = FileProvider.getUriForFile(getActivity(), /* 'getActivity' was 'this' in the example, but this is a fragment, not an activity */
                        "com.example.android.fileprovider",
                        photoFile);
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI);
                startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE);
            }
        }
    }

    // Testing openCV with a filter
    // Don't delete yet, some bmp -> Mat and reverse might be useful for later
    public Bitmap openCvCannyFilter(File imgFile) {
        Mat img = new Mat();

        // Get the path of the image file and covert the file to a bitmap
        Bitmap srcBitmap = BitmapFactory.decodeFile(imgFile.getAbsolutePath());
        Bitmap bmp32 = srcBitmap.copy(Bitmap.Config.ARGB_8888, true);

        // Convert the bitmap to a Mat
        Utils.bitmapToMat(bmp32, img);

        // OpenCV stuff
        Imgproc.cvtColor(img, img, Imgproc.COLOR_RGB2BGRA);
        Mat img_result = img.clone();
        Imgproc.Canny(img, img_result, 80, 90);
        Bitmap img_bitmap = Bitmap.createBitmap(img_result.cols(), img_result.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(img_result, img_bitmap);

        return img_bitmap;
    }

    public void checkImageForChessboard() {
        boolean found = Calib3d.findChessboardCorners(mRGBA, boardSize, imageCorners, Calib3d.CALIB_CB_ADAPTIVE_THRESH + Calib3d.CALIB_CB_NORMALIZE_IMAGE + Calib3d.CALIB_CB_FAST_CHECK);
        if(found) {
            Log.i("Chessboard", "Chessboard found");

            imagePoints.add(imageCorners);
            imageCornerCopy = imageCorners;

            imageCorners = new MatOfPoint2f();
            objectPoints.add(obj);
            mRGBA.copyTo(savedImage);

            imgCounter++;
            txtImgCounter.setText(imgCounter + " / 30");
        } else {
            Log.i("Chessboard", "Chessboard not found");
        }
    }

    // Detecting the chessboard pattern in a Mat variable, corners are saved to the imageCorners variable, returns true if chessboard is detected
    public boolean chessboardDetection(Mat img_result) {
        // TODO: Everything in this method on another thread
        boolean found = Calib3d.findChessboardCorners(img_result, boardSize, imageCorners, Calib3d.CALIB_CB_ADAPTIVE_THRESH + Calib3d.CALIB_CB_NORMALIZE_IMAGE + Calib3d.CALIB_CB_FAST_CHECK);

        if(found) {
            // When a chessboard has been detected, save the imageCorners by adding it to the list of corners
            // TODO: (Maybe) Apply functions from https://opencv-java-tutorials.readthedocs.io/en/latest/09-camera-calibration.html for better calibration result (cornerSubPix)

            imagePoints.add(imageCorners);
            imageCornerCopy = imageCorners;

            imageCorners = new MatOfPoint2f(); /* WHYYY???? */
            objectPoints.add(obj);
            img_result.copyTo(savedImage); /* This is for saving the size? There should be an easier way than saving every time */
        }
        img_result.release(); /* Release the matrix manually, since Java doesn't detect the size behind it */
        return found;
    }

    @Override
    public void onCameraViewStarted(int width, int height) {
        Log.d("Camera", "onCameraViewStarted");
        mRGBA = new Mat(height, width, CvType.CV_8UC4);
        //Log.i("mRGBA size", "Size on camera start: Height: " + height + ", Width: " + width); /* 720x960 */
    }

    @Override
    public void onCameraViewStopped() {
        Log.d("Camera", "onCameraViewStopped");
        mRGBA.release();
    }

    @Override
    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {

        // TODO: Autofokussierung deaktivieren!

        boolean found;

        // Grab the frame shown in the camera and assign it to the mRGBA variable
        mRGBA = inputFrame.rgba();

        // Create a new Mat for grayscale
        Mat grayImage = new Mat();

        cameraCounter++;
        // Limit the fps, increase the number for less fps -> should help with processing speed
        if(cameraCounter < 6 || calibInProgress) {
            return null;
        } else {
            if(!calibrated) {
                // Convert to gray image for faster processing
                //Imgproc.cvtColor(mRGBA, grayImage, Imgproc.COLOR_BGR2GRAY);

                /*
                // Find the chessboard in the live view
                found = chessboardDetection(grayImage);
                if (found) {
                    Log.d("Chessboard", "Chessboard true");
                    Calib3d.drawChessboardCorners(mRGBA, boardSize, imageCornerCopy, found);
                } else {
                    Log.d("Chessboard", "Chessboard false");
                }*/
                return mRGBA;
            } else {
                Mat undistorted = new Mat();

                if(debug) {
                    Log.i("intrinsic", intrinsic.toString());
                    Log.i("intrinsic", intrinsic.dump());
                    debug = false;
                }

                Calib3d.undistort(mRGBA, undistorted, intrinsic, distCoeffs);

                return undistorted;
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d("onDestroy", "onDestroy");
        if (javaCameraView != null)
        {
            javaCameraView.disableView();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        Log.d("onPause", "onPause");
        if (javaCameraView != null)
        {
            javaCameraView.disableView();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.d("onResume", "onResume");
        if (OpenCVLoader.initDebug())
        {
            Log.d("OpenCV", "OpenCV is initialised again");
            baseLoaderCallback.onManagerConnected((BaseLoaderCallback.SUCCESS));
        }
        else
        {
            Log.d("OpenCV", "OpenCV is not working");
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION, getContext(), baseLoaderCallback);
        }
    }

    public void calibrateCamera() {
        // Not disabling the camera froze the app previously, now it works. Disabling might still be advisable, because the calibration takes a moment.
        //javaCameraView.disableView();

        // Create a new thread to calculate the result in that is no the main UI thread. This makes the calculation faster and stops the app from freezing.
        Thread calibrateThread = new Thread() {
            public void run() {

                List<Mat> rvecs = new ArrayList<>();
                List<Mat> tvecs = new ArrayList<>();
                // TODO: Focal length = fx, fy
                intrinsic.put(0, 0, 1);
                intrinsic.put(1, 1, 1);
                // calibrate
                Calib3d.calibrateCamera(objectPoints, imagePoints, savedImage.size(), intrinsic, distCoeffs, rvecs, tvecs);

                Message message = new Message();
                Bundle bundle = new Bundle();
                bundle.putString("Calibrationresult", "Success");
                message.setData(bundle);
                handler.sendMessage(message);
            }
        };

        calibrateThread.start();
    }

    private Handler handler = new Handler() {
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            Bundle bundle = msg.getData();
            String result = bundle.getString("CalibrationResult");
            Log.i("CalibrationResult", "Calibration successful");
            Log.i("distCoeffs", distCoeffs.dump());

            Log.i("distCoeffs", distCoeffs.toString());

            // Save the values of the distortion matrix to shared preferences
            SharedPreferences prefs = getActivity().getPreferences(Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            for(int i = 0; i < 5; i++) {
                double data = distCoeffs.get(0, i)[0];
                Log.i("distCoeffs", "Double: " + data);
                //Log.i("distCoeffs", "String: " + String.valueOf(data));
                //Log.i("distCoeffs", "Back to Double: " + Double.valueOf(String.valueOf(data)));
                editor.putString("distCoeffs"+i, String.valueOf(data));
            }
            for(int i = 0; i < 3; i++) {
                for(int j = 0; j < 3; j++) {
                    double data = intrinsic.get(i, j)[0];
                    editor.putString("intrinsic"+i+j, String.valueOf(data));
                }
            }
            editor.apply();

            calibrated = true;
            calibInProgress = false;


            loadingSpinner.setVisibility(View.INVISIBLE);
            btnVerify.setVisibility(View.VISIBLE);
        }
    };
}