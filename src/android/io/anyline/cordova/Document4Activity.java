package io.anyline.cordova;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PointF;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import androidx.core.content.ContextCompat;
import at.nineyards.anyline.camera.CameraController;
import at.nineyards.anyline.camera.CameraOpenListener;
import at.nineyards.anyline.core.LicenseException;
import at.nineyards.anyline.models.AnylineImage;
import at.nineyards.anyline.util.TempFileUtil;
import io.anyline.AnylineSDK;
import io.anyline.plugin.ScanResult;
import io.anyline.plugin.document.DocumentScanResultListener;
import io.anyline.plugin.document.DocumentScanViewPlugin;
import io.anyline.view.LicenseKeyExceptionListener;
import io.anyline.view.ScanView;

/**
 * Example activity for the Anyline-Document-Detection-Module
 */
public class Document4Activity extends AnylineBaseActivity implements CameraOpenListener, LicenseKeyExceptionListener {

    private static final long ERROR_MESSAGE_DELAY = 2000;
    private static final String TAG = Document4Activity.class.getSimpleName();
    private ScanView documentScanView;
    private Toast notificationToast;
    private ImageView imageViewResult;
    private ProgressDialog progressDialog;
    private List<PointF> lastOutline;
    private ObjectAnimator errorMessageAnimator;
    private FrameLayout errorMessageLayout;
    private TextView errorMessage;
    private long lastErrorRecieved = 0;
    private int quality = 100;
    private Runnable errorMessageCleanup;
    private ImageButton btnCapture;
    private ImageButton btnFinish;
    JSONObject jsonResult;
    private Boolean cancelOnResult = true;


    private android.os.Handler handler = new android.os.Handler();


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(getResources().getIdentifier("activity_scan_document", "layout", getPackageName()));

        // takes care of fading the error message out after some time with no error reported from the SDK
        errorMessageCleanup = new Runnable() {
            @Override
            public void run() {
                if (Document4Activity.this.isFinishing()) {
                    return;
                }
                if (System.currentTimeMillis() > lastErrorRecieved + ERROR_MESSAGE_DELAY) {
                    if (errorMessage == null || errorMessageAnimator == null) {
                        return;
                    }
                    if (errorMessage.getAlpha() == 0f) {
                        errorMessage.setText("");
                    } else if (!errorMessageAnimator.isRunning()) {
                        errorMessageAnimator = ObjectAnimator.ofFloat(errorMessage, "alpha", errorMessage.getAlpha(), 0f);
                        errorMessageAnimator.setDuration(ERROR_MESSAGE_DELAY);
                        errorMessageAnimator.setInterpolator(new AccelerateInterpolator());
                        errorMessageAnimator.start();
                    }
                }
                handler.postDelayed(errorMessageCleanup, ERROR_MESSAGE_DELAY);
            }
        };

        // Set the flag to keep the screen on (otherwise the screen may go dark during scanning)
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        imageViewResult = (ImageView) findViewById(getResources().getIdentifier("image_result", "id", getPackageName()));
        errorMessageLayout = (FrameLayout) findViewById(getResources().getIdentifier("error_message_layout", "id", getPackageName()));
        errorMessage = (TextView) findViewById(getResources().getIdentifier("error_message", "id", getPackageName()));

        documentScanView = (ScanView) findViewById(getResources().getIdentifier("document_scan_view", "id", getPackageName()));
        // add a camera open listener that will be called when the camera is opened or an error occurred
        // this is optional (if not set a RuntimeException will be thrown if an error occurs)
        documentScanView.setCameraOpenListener(this);
        // the view can be configured via a json file in the assets, and this config is set here

        try {
            AnylineSDK.init(licenseKey, this);
        } catch (LicenseException e) {
            finishWithError(Resources.getString(this, "error_license_init"));
        }

        JSONObject json = null;
        try {
            json = new JSONObject(configJson);
            documentScanView.setScanConfig(json);
        } catch (Exception e) {
            e.printStackTrace();
            Log.i(TAG, "Exception: " + e);
        }

        cancelOnResult = true;
        JSONObject jsonObject;
        try {
            jsonObject = new JSONObject(configJson);
            // cancelOnResult is defined in section viewPlugin, so get it from there:
            JSONObject viewPluginJson = jsonObject.optJSONObject("viewPlugin");

            cancelOnResult = viewPluginJson.getBoolean("cancelOnResult");
        } catch (Exception e) {
            Log.d(TAG, e.getLocalizedMessage());
        }

        btnCapture = findViewById(getResources().getIdentifier("capture", "id", getPackageName()));
        btnFinish = findViewById(getResources().getIdentifier("finish", "id", getPackageName()));
        // get Document specific Configs
        if (json.has("document")) {
            try {
                // Get the Document specific Config
                JSONObject documentConfig = json.getJSONObject("document");

                // set manual capture Button Config
                if (documentConfig.has("manualCaptureButton")) {
                    JSONObject manCapBtnConf = documentConfig.getJSONObject("manualCaptureButton");

                    if (manCapBtnConf.has("buttonColor")) {
                        //btnCapture.setBackgroundColor(Color.parseColor("#" + manCapBtnConf.getString("buttonColor")));
                        btnCapture.setColorFilter(Color.parseColor("#" + manCapBtnConf.getString("buttonColor")));
                    }

                    // init Manual Capture Button
                    btnCapture.setVisibility(View.VISIBLE);
                    btnCapture.setOnClickListener(new View.OnClickListener() {

                        @Override
                        public void onClick(View v) {
                            btnCapture.setClickable(false);
                            documentScanView.stop();
                            Log.i(TAG, "manualScan documentScanView.stop()");

                            ((DocumentScanViewPlugin) documentScanView.getScanViewPlugin()).triggerPictureCornerDetection();
                        }
                    });

                } else {
                    btnCapture.setVisibility(View.GONE);
                }

                // show finish Button only if defined in config and if continuous scanning (cancelOnResult = false):
                if (documentConfig.has("finishedButton") && !cancelOnResult) {
                    JSONObject finishedBtnConf = documentConfig.getJSONObject("finishedButton");

                    if (finishedBtnConf.has("buttonColor")) {
                        btnFinish.setColorFilter(Color.parseColor("#" + finishedBtnConf.getString("buttonColor")));
                    }

                    // init finish Button
                    btnFinish.setVisibility(View.VISIBLE);
                    btnFinish.setOnClickListener(new View.OnClickListener() {

                        @Override
                        public void onClick(View v) {
                            finish();
                        }
                    });

                } else {
                    btnFinish.setVisibility(View.GONE);
                }
            } catch (JSONException e) {
                Log.e(TAG, e.getMessage());
            }
        }

        // initialize Anyline with the license key and a Listener that is called if a result is found
        documentScanView.getScanViewPlugin().addScanResultListener(new DocumentScanResultListener() {
            @Override
            public void onResult(ScanResult documentResult) {
                Log.i(TAG, "******** On Result");

                // handle the result document images here
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }
                btnCapture.setClickable(false);

                AnylineImage transformedImage = (AnylineImage) documentResult.getResult();
                AnylineImage fullFrame = documentResult.getFullImage();

                imageViewResultSetImageBitmap(transformedImage);

                /**
                 * IMPORTANT: cache provided frames here, and release them at the end of this onResult. Because
                 * keeping them in memory (e.g. setting the full frame to an ImageView)
                 * will result in a OutOfMemoryError soon. This error is reported in {@link #onTakePictureError
                 * (Throwable)}
                 *
                 * Use a DiskCache http://developer.android.com/training/displaying-bitmaps/cache-bitmap.html#disk-cache
                 * for example
                 *
                 */
                File outDir = new File(getCacheDir(), "ok");
                outDir.mkdir();
                // change the file ending to png if you want a png
                //JSONObject
                jsonResult = new JSONObject();
                try {
                    // convert the transformed image into a gray scaled image internally
                    // transformedImage.getGrayCvMat(false);
                    // get the transformed image as bitmap
                    // Bitmap bmp = transformedImage.getBitmap();
                    // save the image with quality 100 (only used for jpeg, ignored for png)
                    File imageFile = TempFileUtil.createTempFileCheckCache(Document4Activity.this,
                                                                           UUID.randomUUID().toString(), ".jpg");
                    transformedImage.save(imageFile, quality);
//                    showToast(getString(
//                            getResources().getIdentifier("document_image_saved_to", "string", getPackageName())) + " " + imageFile.getAbsolutePath());

                    jsonResult.put("imagePath", imageFile.getAbsolutePath());

                    // Save the Full Frame Image
                    if (fullFrame != null) {
                        imageFile = TempFileUtil.createTempFileCheckCache(Document4Activity.this,
                                                                          UUID.randomUUID().toString(), ".jpg");
                        fullFrame.save(imageFile, quality);
                        jsonResult.put("fullImagePath", imageFile.getAbsolutePath());
                    }
                    // Put outline and conficence to result
                    jsonResult.put("outline", jsonForOutline(documentResult.getOutline()));
                    jsonResult.put("confidence", documentResult.getConfidence());
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (JSONException jsonException) {
                    //should not be possible
                    Log.e(TAG, "Error while putting image path to json.", jsonException);
                }

                // release the images
                transformedImage.release();
                fullFrame.release();

                if (cancelOnResult) {
                    ResultReporter.onResult(jsonResult, true);
                    setResult(AnylinePlugin.RESULT_OK);
                    finish();
                } else {
                    btnCapture.setClickable(true);
                    ResultReporter.onResult(jsonResult, false);
                }
            }

            @Override
            public void onPreviewProcessingSuccess(AnylineImage anylineImage) {
                // this is called after the preview of the document is completed, and a full picture will be
                // processed automatically
            }

            @Override
            public void onPreviewProcessingFailure(DocumentScanViewPlugin.DocumentError documentError) {
                // this is called on any error while processing the document image
                // Note: this is called every time an error occurs in a run, so that might be quite often
                // An error message should only be presented to the user after some time

                showErrorMessageFor(documentError);
            }

            @Override
            public void onPictureProcessingFailure(DocumentScanViewPlugin.DocumentError documentError) {

                showErrorMessageFor(documentError, true);
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }

                // if there is a problem, here is how images could be saved in the error case
                // this will be a full, not cropped, not transformed image
                AnylineImage image = ((DocumentScanViewPlugin) documentScanView.getScanViewPlugin()).getCurrentFullImage();

                if (image != null) {
                    File outDir = new File(getCacheDir(), "error");
                    outDir.mkdir();
                    File outFile = new File(outDir, "" + System.currentTimeMillis() + documentError.name() + ".jpg");
                    try {
                        image.save(outFile, 100);
                        Log.d(TAG, "error image saved to " + outFile.getAbsolutePath());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    image.release();
                }
            }

            @Override
            public boolean onDocumentOutlineDetected(List rect, boolean documentShapeAndBrightnessValid) {
                // is called when the outline of the document is detected. return true if the outline is consumed by
                // the implementation here, false if the outline should be drawn by the DocumentScanView
                lastOutline = rect; // saving the outline for the animations
                return false;
            }

            @Override
            public void onTakePictureSuccess() {
                // this is called after the image has been captured from the camera and is about to be processed
                // handle the result document images here
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }

                // do not display error messages while "processing picture" progress dialog is shown:
                errorMessageLayout.setVisibility(View.GONE);

                progressDialog = ProgressDialog.show(Document4Activity.this, getString(
                        getResources().getIdentifier("document_processing_picture_header", "string", getPackageName())),
                                                     getString(
                                                             getResources().getIdentifier("document_processing_picture", "string", getPackageName())),
                                                     true);

                // there is a bug in the sdk that onTakePictureSuccess is called but onResult not.
                // so implement a workaround: hide the progressDialog after 2 seconds, the phone will continue scanning
                final Handler handler1 = new Handler();
                final Runnable runnable = new Runnable() {
                    @Override
                    public void run() {
                        if (progressDialog.isShowing()) {
                            progressDialog.dismiss();
                        }
                    }
                };

                progressDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        handler1.removeCallbacks(runnable);
                    }
                });

                handler1.postDelayed(runnable, 2000);
                // workaround end

                if (errorMessageAnimator != null && errorMessageAnimator.isRunning()) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            errorMessageAnimator.cancel();
                            errorMessageLayout.setVisibility(View.GONE);
                        }
                    });
                }

            }

            @Override
            public void onTakePictureError(Throwable throwable) {
                // This is called if the image could not be captured from the camera (most probably because of an
                // OutOfMemoryError)
                throw new RuntimeException(throwable);
            }

            @Override
            public void onPictureCornersDetected(AnylineImage anylineImage, List list) {
                // this is called after manual corner detection was requested

                // save fullFrame
                //JSONObject
                Log.i(TAG, "manualScan onPictureCornersDetected");

                jsonResult = new JSONObject();

                try {
                    File imageFile = TempFileUtil.createTempFileCheckCache(Document4Activity.this, UUID.randomUUID().toString(), ".jpg");
                    anylineImage.save(imageFile, quality);
                    //manualResult.put("fullImagePath", imageFile.getAbsolutePath());
                    jsonResult.put("fullImagePath", imageFile.getAbsolutePath());
                    jsonResult.put("outline", jsonForOutline(list));
                    Log.i(TAG, "manualScan try: save full image success");
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (JSONException e) {
                    e.printStackTrace();
                }

                ((DocumentScanViewPlugin) documentScanView.getScanViewPlugin()).transformPicture(anylineImage, list);
                anylineImage.release();
            }


            @Override
            public void onPictureTransformed(AnylineImage anylineImage) {
                // this is called after a full frame image and 4 corners were passed to the SDK for
                // transformation (e.g. when a user manually selected the corners in an image)

                // handle the result document images here
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }
                Log.i(TAG, "manualScan onPictureTransformed");

                imageViewResultSetImageBitmap(anylineImage);

                // save fullFrame
                try {
                    File imageFile = TempFileUtil.createTempFileCheckCache(Document4Activity.this, UUID.randomUUID().toString(), ".jpg");
                    anylineImage.save(imageFile, quality);
                    jsonResult.put("imagePath", imageFile.getAbsolutePath());

                    //                    if (showSuccessToast) {
                    //                        // Only show toast if user has specified it should be shown
                    //                        showToast(getString(getResources().getIdentifier("document_image_saved_to", "string", getPackageName())) + " " + imageFile.getAbsolutePath());
                    //                    }
                    Log.i(TAG, "manualScan try: save cropped image success");

                } catch (IOException e) {
                    e.printStackTrace();
                } catch (JSONException e) {
                    e.printStackTrace();
                }

                anylineImage.release();

                if (cancelOnResult) {
                    ResultReporter.onResult(jsonResult, true);
                    setResult(AnylinePlugin.RESULT_OK);
                    finish();
                } else {
                    btnCapture.setClickable(true);
                    ResultReporter.onResult(jsonResult, false);
                    documentScanView.start();
                    Log.i(TAG, "manualScan documentScanView.start()");
                }
            }

            @Override
            public void onPictureTransformError(DocumentScanViewPlugin.DocumentError documentError) {
                // this is called on any error while transforming the document image from the 4 corners
                // Note: not implemented in this example
            }

        });
        // optionally stop the scan once a valid result was returned
        // documentScanView.setCancelOnResult(cancelOnResult);

    }

    private void imageViewResultSetImageBitmap(AnylineImage anylineImage) {
        // resize display view based on larger side of document, and display document
        int widthDP, heightDP;
        Bitmap bmpTransformedImage = anylineImage.getBitmap();

        if (bmpTransformedImage.getHeight() > bmpTransformedImage.getWidth()) {
            widthDP = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 100, getResources().getDisplayMetrics());
            heightDP = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 160, getResources().getDisplayMetrics());

            imageViewResult.getLayoutParams().width = widthDP;
            imageViewResult.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
        } else {
            widthDP = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 160, getResources().getDisplayMetrics());
            heightDP = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 100, getResources().getDisplayMetrics());

            imageViewResult.getLayoutParams().width = ViewGroup.LayoutParams.WRAP_CONTENT;
            imageViewResult.getLayoutParams().height = heightDP;
        }

        imageViewResult.setImageBitmap(Bitmap.createScaledBitmap(anylineImage.getBitmap(), widthDP, heightDP, false));

    }


    private void showErrorMessageFor(DocumentScanViewPlugin.DocumentError documentError) {
        showErrorMessageFor(documentError, false);
    }

    private void showErrorMessageFor(DocumentScanViewPlugin.DocumentError documentError, boolean highlight) {
        String text = getString(getResources().getIdentifier("document_picture_error", "string", getPackageName()));
        switch (documentError) {
            case DOCUMENT_NOT_SHARP:
                text += getString(getResources().getIdentifier("document_error_not_sharp", "string", getPackageName()));
                break;
            case DOCUMENT_SKEW_TOO_HIGH:
                text += getString(getResources().getIdentifier("document_error_skew_too_high", "string", getPackageName()));
                break;
            case DOCUMENT_OUTLINE_NOT_FOUND:
                //text += getString(R.string.document_error_outline_not_found);
                return; // exit and show no error message for now!
            case IMAGE_TOO_DARK:
                text += getString(getResources().getIdentifier("document_error_too_dark", "string", getPackageName()));
                break;
            case SHAKE_DETECTED:
                text += getString(getResources().getIdentifier("document_error_shake", "string", getPackageName()));
                break;
            case DOCUMENT_BOUNDS_OUTSIDE_OF_TOLERANCE:
                text += getString(getResources().getIdentifier("document_error_closer", "string", getPackageName()));
                break;
            case DOCUMENT_RATIO_OUTSIDE_OF_TOLERANCE:
                text += getString(getResources().getIdentifier("document_error_format", "string", getPackageName()));
                break;
            case UNKNOWN:
                break;
            default:
                text += getString(getResources().getIdentifier("document_error_unknown", "string", getPackageName()));
                return; // exit and show no error message for now!
        }

        if (highlight) {
            showHighlightErrorMessageUiAnimated(text);
        } else {
            showErrorMessageUiAnimated(text);
        }
    }

    private void showErrorMessageUiAnimated(String message) {
        if (lastErrorRecieved == 0) {
            // the cleanup takes care of removing the message after some time if the error did not show up again
            handler.post(errorMessageCleanup);
        }
        lastErrorRecieved = System.currentTimeMillis();
        if (errorMessageAnimator != null && (errorMessageAnimator.isRunning() || errorMessage.getText().equals
                (message))) {
            return;
        }

        errorMessageLayout.setVisibility(View.VISIBLE);
        errorMessage.setBackgroundColor(ContextCompat.getColor(this, getResources().getIdentifier("anyline_blue_darker", "color", getPackageName())));
        errorMessage.setAlpha(0f);
        errorMessage.setText(message);
        errorMessageAnimator = ObjectAnimator.ofFloat(errorMessage, "alpha", 0f, 1f);
        errorMessageAnimator.setDuration(ERROR_MESSAGE_DELAY);
        errorMessageAnimator.setInterpolator(new DecelerateInterpolator());
        errorMessageAnimator.start();
    }

    private void showHighlightErrorMessageUiAnimated(String message) {
        lastErrorRecieved = System.currentTimeMillis();
        errorMessageLayout.setVisibility(View.VISIBLE);
        errorMessage.setBackgroundColor(ContextCompat.getColor(this, getResources().getIdentifier("anyline_red", "color", getPackageName())));
        errorMessage.setAlpha(0f);
        errorMessage.setText(message);

        if (errorMessageAnimator != null && errorMessageAnimator.isRunning()) {
            errorMessageAnimator.cancel();
        }

        errorMessageAnimator = ObjectAnimator.ofFloat(errorMessage, "alpha", 0f, 1f);
        errorMessageAnimator.setDuration(ERROR_MESSAGE_DELAY);
        errorMessageAnimator.setInterpolator(new DecelerateInterpolator());
        errorMessageAnimator.setRepeatMode(ValueAnimator.REVERSE);
        errorMessageAnimator.setRepeatCount(1);
        errorMessageAnimator.start();
    }

    private void showToast(String text) {
        try {
            notificationToast.setText(text);
        } catch (Exception e) {
            notificationToast = Toast.makeText(this, text, Toast.LENGTH_SHORT);
        }
        notificationToast.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        //start the actual scanning
        documentScanView.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        //stop the scanning
        documentScanView.stop();
        //release the camera (must be called in onPause, because there are situations where
        // it cannot be auto-detected that the camera should be released)
        documentScanView.releaseCameraInBackground();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    public void onCameraOpened(CameraController cameraController, int width, int height) {
        //the camera is opened async and this is called when the opening is finished
        Log.d(TAG, "Camera opened successfully. Frame resolution " + width + " x " + height);
    }

    @Override
    public void onCameraError(Exception e) {
        //This is called if the camera could not be opened.
        // (e.g. If there is no camera or the permission is denied)
        // This is useful to present an alternative way to enter the required data if no camera exists.
        throw new RuntimeException(e);
    }

    @Override
    public void licenseKeyCheck(LicenseException licenseCheck) {
        finishWithError(licenseCheck.getLocalizedMessage());
    }

}
