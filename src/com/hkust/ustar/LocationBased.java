// Copyright 2007-2013 metaio GmbH. All rights reserved.
package com.hkust.ustar;

import java.io.FileOutputStream;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnLongClickListener;
import android.view.Window;
import android.view.WindowManager;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.hkust.ustar.PedometerDialog.onDialogResult;
import com.hkust.ustar.R;
import com.hkust.ustar.database.DatabaseHelper;
import com.hkust.ustar.verticalscrollview.CenterLockVerticalScrollView;
import com.hkust.ustar.verticalscrollview.CustomListAdapter;
import com.hkust.ustar.verticalscrollview.CustomListAdapter.Holder;
import com.metaio.sdk.ARViewActivity;
import com.metaio.sdk.MetaioDebug;
import com.metaio.sdk.jni.EPLAYBACK_STATUS;
import com.metaio.sdk.jni.ETRACKING_STATE;
import com.metaio.sdk.jni.EVISUAL_SEARCH_STATE;
import com.metaio.sdk.jni.IGeometry;
import com.metaio.sdk.jni.IMetaioSDKCallback;
import com.metaio.sdk.jni.IRadar;
import com.metaio.sdk.jni.IVisualSearchCallback;
import com.metaio.sdk.jni.LLACoordinate;
import com.metaio.sdk.jni.MovieTextureStatus;
import com.metaio.sdk.jni.Rotation;
import com.metaio.sdk.jni.SensorValues;
import com.metaio.sdk.jni.TrackingValues;
import com.metaio.sdk.jni.TrackingValuesVector;
import com.metaio.sdk.jni.Vector3d;
import com.metaio.sdk.jni.VisualSearchResponseVector;
import com.metaio.tools.io.AssetsManager;

public class LocationBased extends ARViewActivity
{
	// data structure
	private SQLiteDatabase mDatabase;
	private ArrayList<String[]> mPathInstructions;
	protected ArrayList<Integer> mNodeConversionTable;
	private ArrayList<String> mPathDistances;
	private HashMap <Integer, List<IGeometry>> mFacilityHashMap;
    private SharedPreferences mPrefs;
    public HashMap <String,String> mLTHashMap = new HashMap<String,String>();
    
    // gui components
	private CenterLockVerticalScrollView centerLockVerticalScrollView;
	private CustomListAdapter customListAdapter;
	private Button btnPrev, btnNext;
	private Toast mToast;
	
	protected String mPath;
	private final int mRadius = 10;
	protected int mDestinationNID;
	protected int mSourceNID;
	private int mTotalDistance;
	private int mSelectedFacility = -1;
	protected int mCurrIndex = -1;
	protected double mCurrentLatitude;
	protected double mCurrentLongitude;
	private double mArrowLatitudeAdjust;
	private double mArrowLongitudeAdjust;
	private boolean mIsFirstRun = true;
	private double longitudeAdjust = 1.0f;
	private int currentFloor;
	double sourceFloor;
	double targetFloor;
	
	// metaio related variables
	boolean mCVSrequest;
	private VisualSearchCallbackHandler mVSCallback;
	private MetaioSDKCallbackHandler mCallbackHandler;
	private TrackingValues m_trackingValues;
	private final static String databaseID = "ustarCVS";
	private IGeometry mMovieModel;
	private IGeometry mArrowGeometry;
	private IRadar mRadar;
	protected LLACoordinate mNextTargetLocation;
	private Paint mPaint;
	
	// pedometer related varialbes
	protected StepDetector mStepDetector;
    protected SensorManager mSensorManager;
    private PedometerDialog mPedometerDialog;
    private FacilityDialog mFacilityDialog; 
    private TextView mStepsTextView;
    public String KEY_PEDOMETER_SENSITIVITY = "preferences_pedometer_sensitivity";
    public String KEY_PEDOMETER_HEIGHT = "preferences_pedometer_height";
    public String KEY_PEDOMETER_AUTO_UPDATE = "preferences_pedometer_auto_update";
    public String KEY_PEDOMETER_TTS = "preferences_pedometer_tts";
    private String mNextTurn;
    protected int mStepsRemaining;
    protected int mStepsToNextPoint;
    private int mSensitivity;
    private int mUserHeight;
    private double STEP_LENGTH_PER_METER = 1;
    private double mStepDistance;
    private boolean mAutoUpdateEnabled;
    private boolean mTTSEnabled;
    private TextToSpeech mTTS;
    
    private ARViewActivity myAct = this;
    // rendering limit
//    private int mRenderingLimitMin;
//    private int mRenderingLimitMax;
//    private int mClippingLimitMin;
//    private int mClippingLimitMax;
//    private SeekBar mRenderingLimitMinSeekbar;
//    private SeekBar mRenderingLimitMaxSeekbar;
//    private SeekBar mClippingLimitMinSeekbar;
//    private SeekBar mClippingLimitMaxSeekbar;
    
	@Override
	public void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		// set the window to stay on all the time
	    Window window = getWindow();
	    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
	    setContentView(R.layout.location_based);
	    
		// hide all gui components while loading
		mGUIView.setVisibility(View.GONE);
		
		// initiate metaio cvs
		mCallbackHandler = new MetaioSDKCallbackHandler();
		     
	    // use a single toast to avoid overlapping 
	    mToast = Toast.makeText(getApplicationContext(), null, Toast.LENGTH_SHORT);
	        
		// initiate step detector for pedometer
		mStepDetector = new StepDetector();
		mSensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
		
		mTTS = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
			@Override
			public void onInit(int status) {
				if (status == TextToSpeech.SUCCESS) {
		            int result = mTTS.setLanguage(Locale.US);
		            mTTS.setSpeechRate((float) 0.8);
		            if (result == TextToSpeech.LANG_MISSING_DATA
		                    || result == TextToSpeech.LANG_NOT_SUPPORTED) {
		                Log.e("TTS", "This Language is not supported");
		            }		 
		        } else {
		            Log.e("TTS", "Initilization Failed!");
		        }
			}
		});
		
	    // initiate database handler
	    try {
			DatabaseHelper mDatabaseHelper = DatabaseHelper.getInstance(this);
			mDatabase = mDatabaseHelper.getDatabase();
		} catch (IOException e2) {
			e2.printStackTrace();
		}
	    
		// Intent intent = new Intent(getApplicationContext(), GestureActivity.class);
		// startActivity(intent);
	}
	
	@Override
	protected void onResume() {
		super.onResume();
	    // retrieve the preference values
	    retrievePreferences();
	    // register sensors
	    registerDetector();
	    // fetch the new destination and source ids from the previous intent
		int new_destination = getIntent().getExtras().getInt("my_destination");
		int new_source = getIntent().getExtras().getInt("my_source");
		// if the request for navigation is different from that of the previous
		if(!(new_destination == mDestinationNID && new_source == mSourceNID)) {
			mDestinationNID = new_destination;
			mSourceNID = new_source;
			
			mPath = getIntent().getExtras().getString("my_path");
			// manually set my current location
			mCurrentLatitude = getIntent().getExtras().getDouble("my_source_latitude");
			mCurrentLongitude = getIntent().getExtras().getDouble("my_source_longitude");
			mSensors.setManualLocation(new LLACoordinate(mCurrentLatitude, mCurrentLongitude, 0, 0));
			
			// GPS tracking configuration must be set on user-interface thread
			boolean result = metaioSDK.setTrackingConfiguration("GPS");
			MetaioDebug.log("Tracking data loaded: " + result);
			
			// reset all variables
			mStepsRemaining = 0;
			mNextTurn = "";
			mPathInstructions = new ArrayList<String[]>();
			mNodeConversionTable = new ArrayList<Integer>();
			mPathDistances = new ArrayList<String>();
			mTotalDistance = 0;

			Log.i("mag","Before getPathInstructions()");
			getPathInstructions();
			Log.i("mag","After getPathInstructions()");
			
			// initially, the first instruction is on focus
			if(!mIsFirstRun) {
				runOnUiThread(new Runnable(){
					@Override
					public void run() {
						updateVerticalScrollView();
					}
				});
				if(mSourceNID != mDestinationNID) {
					// show the scroll view and focus on the first item
					btnNext.setVisibility(View.VISIBLE);
					btnPrev.setVisibility(View.VISIBLE);
					centerLockVerticalScrollView.setVisibility(View.VISIBLE);
					mArrowGeometry.setVisible(true);
					centerLockVerticalScrollView.getItem(0).performClick();
				}
				else {
					// if the source and destination ids are equal, update step counter to 0
					updateStepCounter();
					// hide the scroll view
					btnNext.setVisibility(View.GONE);
					btnPrev.setVisibility(View.GONE);
					centerLockVerticalScrollView.setVisibility(View.GONE);
					mArrowGeometry.setVisible(false);
				}
			}
		}
	}
	
	@Override
	protected void onDestroy() 
	{
		super.onDestroy();
		
		mCallbackHandler.delete();
		mCallbackHandler = null;
		
		mVSCallback.delete();
		mVSCallback = null;
		
        // stop pedometer
        mSensorManager.unregisterListener(mStepDetector);
	}
	
	@Override
	public void onDrawFrame() 
	{
		m_trackingValues = metaioSDK.getTrackingValues(1);
		// request new VisualSearch before rendering next frame
		if (mCVSrequest || !m_trackingValues.isTrackingState())
		{
			metaioSDK.requestVisualSearch(databaseID, true);
			mCVSrequest = false;
		}
		
		if (metaioSDK != null && mSensors != null)
		{
			SensorValues sensorValues = mSensors.getSensorValues();

			float heading = 0.0f;
			if (sensorValues.hasAttitude())
			{
				float m[] = new float[9];
				sensorValues.getAttitude().getRotationMatrix(m);

				Vector3d v = new Vector3d(m[6], m[7], m[8]);
				v = v.normalize();

				heading = (float)(-Math.atan2(v.getY(), v.getX()) - Math.PI/2.0);
			}

			
			Rotation rot = new Rotation((float)(Math.PI/2.0), 0.0f, -heading);
			for(int i = 0 ; i < 6; i++) {
				List <IGeometry> list = mFacilityHashMap.get(i);
				for (IGeometry geo : list)
				{
					if (geo != null)
					{
						geo.setRotation(rot);
					}
				}
			}
		}
		
		super.onDrawFrame();
	}
	
	@Override
	public void onBackPressed(){
		// stop pedometer
        mSensorManager.unregisterListener(mStepDetector);
        
		Intent intent = new Intent(this, CaptureActivity.class);
	    intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
	    startActivity(intent);
	}
	
	@Override
	protected void onNewIntent(Intent intent) {
	    super.onNewIntent(intent);
	    setIntent(intent);
	}
	
	private void registerDetector() {
		// register accelerometer for pedometer
        //Sensor.TYPE_MAGNETIC_FIELD | Sensor.TYPE_ORIENTATION
        mSensorManager.registerListener(mStepDetector, mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD), SensorManager.SENSOR_DELAY_FASTEST);
        mSensorManager.registerListener(mStepDetector, mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_FASTEST);
    }
	
	public void onMapButtonClick(View v) throws IOException {
		// stop pedometer
        mSensorManager.unregisterListener(mStepDetector);
		
		Intent intent = new Intent(getApplicationContext(), TouchImageViewActivity.class);
		intent.putExtra("my_path", mPath);
		intent.putExtra("curr_floor", currentFloor);
		if(mSourceNID != mDestinationNID) {
			intent.putExtra("curr_position", mNodeConversionTable.get(mCurrIndex));
			intent.putExtra("percentage_traveled", (1 - ((double)mStepsRemaining / mStepsToNextPoint)));
			intent.putExtra("next_latitude", mNextTargetLocation.getLatitude());
			intent.putExtra("next_longitude", mNextTargetLocation.getLongitude());
		}
		else {
			intent.putExtra("curr_position", 0);			
			intent.putExtra("percentage_traveled", 0);
			intent.putExtra("next_latitude", mCurrentLatitude);
			intent.putExtra("next_longitude", mCurrentLongitude);
		}

		startActivity(intent);
	}
	
	@Override
	protected int getGUILayout() 
	{
		return R.layout.location_based;
	}

	@Override
	protected IMetaioSDKCallback getMetaioSDKCallbackHandler() 
	{
		return mCallbackHandler;
	}
	
	@Override
	protected void loadContents() 
	{
		// Executed only in the first run after onResume
		try
		{
			runOnUiThread(new Runnable(){
				@Override
				public void run() {
					btnNext = (Button) findViewById(R.id.btnNext);
					btnPrev = (Button) findViewById(R.id.btnPrev);
					btnNext.setOnClickListener(onScrollItemClickListener);
					btnPrev.setOnClickListener(onScrollItemClickListener);
					centerLockVerticalScrollView = (CenterLockVerticalScrollView) findViewById(R.id.scrollView);
					
					if(mSourceNID == mDestinationNID) {
						btnNext.setVisibility(View.GONE);
						btnPrev.setVisibility(View.GONE);
						centerLockVerticalScrollView.setVisibility(View.GONE);
						if(mArrowGeometry != null)
							mArrowGeometry.setVisible(false);
					}
					else {
						btnNext.setVisibility(View.VISIBLE);
						btnPrev.setVisibility(View.VISIBLE);
						centerLockVerticalScrollView.setVisibility(View.VISIBLE);
						if(mArrowGeometry != null)
							mArrowGeometry.setVisible(true);
					}
					
					updateVerticalScrollView();
					
					// initialize steps text view
				    mStepsTextView = (TextView) findViewById(R.id.steps_text_view);
				    Typeface tf = Typeface.createFromAsset(getAssets(), "fonts/ostrich-regular.ttf");				
				    mStepsTextView.setTypeface(tf);
				    
//				    mRenderingLimitMinSeekbar = (SeekBar)findViewById(R.id.renderingmin_seekbar);
//				    mRenderingLimitMinSeekbar.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
//
//						@Override
//						public void onProgressChanged(SeekBar seekBar,
//								int progress, boolean fromUser) {
//							// TODO Auto-generated method stub
//							mRenderingLimitMin = progress;
//							metaioSDK.setLLAObjectRenderingLimits(mRenderingLimitMin, mRenderingLimitMax);
//						}
//
//						@Override
//						public void onStartTrackingTouch(SeekBar seekBar) {
//							// TODO Auto-generated method stub
//							
//						}
//
//						@Override
//						public void onStopTrackingTouch(SeekBar seekBar) {
//							// TODO Auto-generated method stub
//							
//						}
//				    	
//				    });
//				    mRenderingLimitMaxSeekbar = (SeekBar)findViewById(R.id.renderingmax_seekbar);
//				    mRenderingLimitMaxSeekbar.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
//
//						@Override
//						public void onProgressChanged(SeekBar seekBar,
//								int progress, boolean fromUser) {
//							// TODO Auto-generated method stub
//							mRenderingLimitMax = progress;
//							metaioSDK.setLLAObjectRenderingLimits(mRenderingLimitMin, mRenderingLimitMax);
//						}
//
//						@Override
//						public void onStartTrackingTouch(SeekBar seekBar) {
//							// TODO Auto-generated method stub
//							
//						}
//
//						@Override
//						public void onStopTrackingTouch(SeekBar seekBar) {
//							// TODO Auto-generated method stub
//							System.out.println("mRenderingLimitMax:"+mRenderingLimitMax);
//						}
//				    	
//				    });
//				    mClippingLimitMinSeekbar = (SeekBar)findViewById(R.id.clippingmin_seekbar);
//				    mClippingLimitMinSeekbar.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
//
//						@Override
//						public void onProgressChanged(SeekBar seekBar,
//								int progress, boolean fromUser) {
//							// TODO Auto-generated method stub
//							mClippingLimitMin = progress;
//							metaioSDK.setRendererClippingPlaneLimits(mClippingLimitMin, mClippingLimitMax);
//						}
//
//						@Override
//						public void onStartTrackingTouch(SeekBar seekBar) {
//							// TODO Auto-generated method stub
//							
//						}
//
//						@Override
//						public void onStopTrackingTouch(SeekBar seekBar) {
//							// TODO Auto-generated method stub
//							System.out.println("mClippingLimitMin:"+mClippingLimitMin);
//						}
//				    	
//				    });
//				    mClippingLimitMaxSeekbar = (SeekBar)findViewById(R.id.clippingmax_seekbar);
//				    mClippingLimitMaxSeekbar.setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
//
//						@Override
//						public void onProgressChanged(SeekBar seekBar,
//								int progress, boolean fromUser) {
//							// TODO Auto-generated method stub
//							mClippingLimitMax = progress;
//							metaioSDK.setRendererClippingPlaneLimits(mClippingLimitMin, mClippingLimitMax);
//						}
//
//						@Override
//						public void onStartTrackingTouch(SeekBar seekBar) {
//							// TODO Auto-generated method stub
//							
//						}
//
//						@Override
//						public void onStopTrackingTouch(SeekBar seekBar) {
//							// TODO Auto-generated method stub
//							System.out.println("mClippingLimitMax:"+mClippingLimitMax);
//						}
//				    	
//				    });
				}
			});
			// Clamp geometries' Z position to range [5000;200000] no matter how close or far they are away.
			// This influences minimum and maximum scaling of the geometries (easier for development).
			metaioSDK.setLLAObjectRenderingLimits(15, 30);
		    //metaioSDK.setRendererClippingPlaneLimits(1, 1000);
			// Set render frustum accordingly
			//metaioSDK.setRendererClippingPlaneLimits(10, 220000);
			
			mRadar = metaioSDK.createRadar();
			mRadar.setBackgroundTexture(AssetsManager.getAssetPath("metaio/radar.png"));
			mRadar.setObjectsDefaultTexture(AssetsManager.getAssetPath("metaio/yellow.png"));
			mRadar.setRelativeToScreen(IGeometry.ANCHOR_TL);
			
			createFacilityGeometries();
			updateGeometryTranslation();
		}
		catch (Exception e)
		{
			 MetaioDebug.log("Exception: " + e.getMessage());
		}
	}
	
	private String getFilepathForFacilityType(int ftype, boolean male) {
		String filepath = "";
		switch(ftype) {
		case 0:
			filepath = AssetsManager.getAssetPath("metaio/POI/office_poi.png");
			break;
		case 1:
			filepath = AssetsManager.getAssetPath("metaio/POI/lt_poi.png");
			break;
		case 2:
			filepath = AssetsManager.getAssetPath("metaio/POI/room_poi.png");
			break;
		case 3:
			filepath = AssetsManager.getAssetPath("metaio/POI/restaurant_poi.png");
			break;
		case 4:
			if(male)
				filepath = AssetsManager.getAssetPath("metaio/POI/male_poi.png");
			else 
				filepath = AssetsManager.getAssetPath("metaio/POI/female_poi.png");
			break;
		case 5:
			filepath = AssetsManager.getAssetPath("metaio/POI/lift_poi.png");
		  	break;
		  }
		return filepath;
	}
	
	private String getModifiedFacilityName(String fname, int ftype, boolean male) {
        switch(ftype) {
        	case 0:
        		break;
        	case 1:
        		if(fname.length() == 4)
        			fname = mLTHashMap.get(fname);
        		break;
        	case 2:
        		fname = "Room" + fname;
        		break;
        	case 3:
        		break;
        	case 4:
        		if(male)
        			fname = "Male Toilet";
        		else
        			fname = "Female Toilet";
        		break;
        	case 5:
        		break;
        }
        return fname;
	}
	
	private String createBillboardTexture(String billBoardTitle, int ftype)
    {
           try
           {
                  final String texturepath = getCacheDir() + "/" + billBoardTitle + ".png";
                  Paint mPaint = new Paint();
                  // Load background image (256x128), and make a mutable copy
                  Bitmap billboard = null;
                  boolean male = false;
                  
	              if(billBoardTitle.equals("M"))
	                	  male = true;
	              
                  String filepath = getFilepathForFacilityType(ftype, male);
                  billBoardTitle = getModifiedFacilityName(billBoardTitle, ftype, male);
                  Bitmap mBackgroundImage = BitmapFactory.decodeFile(filepath);
                  
                  billboard = mBackgroundImage.copy(Bitmap.Config.ARGB_8888, true);

                  Canvas c = new Canvas(billboard);

                  mPaint.setColor(Color.WHITE);
                  mPaint.setTextSize(24);
                  mPaint.setTypeface(Typeface.DEFAULT);
                  
                  float y = 40;
                  float x = 70;

                  // Draw POI name
                  if (billBoardTitle.length() > 0)
                  {
                        String n = billBoardTitle.trim();

                        final int maxWidth = 170;

                        int i = mPaint.breakText(n, true, maxWidth, null);
                        if (i < n.length())
                        {
                               i = mPaint.breakText(n, true, maxWidth - 15, null);
                               c.drawText(n.substring(0, i) + "..", x, y, mPaint);
                        } else
                        {
                               c.drawText(n.substring(0, i), x, y, mPaint);
                        }
                  }

                  // writing to a file
                  try
                  {
                	  FileOutputStream out = new FileOutputStream(texturepath);
                      billboard.compress(Bitmap.CompressFormat.PNG, 90, out);
                      MetaioDebug.log("Texture file is saved to "+texturepath);
                      return texturepath;
                  } catch (Exception e) {
                      MetaioDebug.log("Failed to save texture file");
                	  e.printStackTrace();
                   }
                 
                  billboard.recycle();
                  billboard = null;

           } catch (Exception e)
           {
                  MetaioDebug.log("Error creating billboard texture: " + e.getMessage());
                  MetaioDebug.printStackTrace(Log.DEBUG, e);
                  return null;
           }
           return null;
    }
	
	@Override
	protected void onGeometryTouched(final IGeometry geometry) 
	{
		Log.d("LLA", "Geometry selected: "+geometry);
		
		//final AlertDialog alertDialog = new AlertDialog.Builder(this).create();
        String gname = geometry.getName();
        
        if(gname.equals("movie")) {
        	// if it is a movie, pause when playing and resume when paused
        	if(mMovieModel != null) {
        		MovieTextureStatus status = mMovieModel.getMovieTextureStatus();
                if (status.getPlaybackStatus() == EPLAYBACK_STATUS.EPLAYBACK_STATUS_PLAYING) {
                	mMovieModel.pauseMovieTexture();
                	mToast.setText("Paused");
                	mToast.show();
                }
                else {
                	mMovieModel.startMovieTexture(true);
                	mToast.setText("Resumed");
                	mToast.show();
                }
        	}
        }
        else if(!gname.equals("arrow")) {
            // if geometry is an arrow, do nothing
			// parse the facility information
        	String[] nid = gname.split(",");
        	callFacilityDialog(nid[0]);
        	/*
	        String[] facility_information = gname.split(",");
	        String fname = facility_information[0];
	        int distance = Integer.parseInt(facility_information[1]);
	        int ftype = Integer.parseInt(facility_information[2]);
	        final String metadata = facility_information[3];
	        boolean male = false;
	        if(fname.equals("M"))
		      	  male = true;
	        
	        // get the modified facility name
	        fname = getModifiedFacilityName(fname, ftype, male);

	        alertDialog.setTitle(fname);
	        if(!metadata.equals("null"))
	        	alertDialog.setMessage(metadata + "\nDistance: " + distance + "m");
	        else
	        	alertDialog.setMessage("Distance: " + distance + "m");
	        
	        // set the appropriate icons
			switch(ftype) {
			case 0:
				// website button to open a url link
		        alertDialog.setButton3("Website", new DialogInterface.OnClickListener() {
		            @Override
					public void onClick(DialogInterface dialog, int which) {
		            	String url = metadata.substring(metadata.indexOf("URL:") + 5);
		            	mSensorManager.unregisterListener(mStepDetector);
		            	Intent i = new Intent(Intent.ACTION_VIEW);
		            	i.setData(Uri.parse(url));
		            	startActivity(i);
		            }    
		        });
				alertDialog.setIcon(R.drawable.office_poi);
				break;
			case 1:
				alertDialog.setIcon(R.drawable.lt_poi);
				break;
			case 2:
				alertDialog.setIcon(R.drawable.room_poi);
				break;
			case 3:
				alertDialog.setIcon(R.drawable.restaurant_poi);
				break;
			case 4:
				if(male)
					alertDialog.setIcon(R.drawable.male_poi);
				else 
					alertDialog.setIcon(R.drawable.female_poi);
				break;
			case 5:
				alertDialog.setIcon(R.drawable.lift_poi);
			  	break;
			  }
			
	        // hide button to set geometry invisible
	        alertDialog.setButton2("Hide", new DialogInterface.OnClickListener() {
	            @Override
				public void onClick(DialogInterface dialog, int which) {
	            	geometry.setVisible(false);
	            }    
	        });
	        
	        // hide the dialog
	        alertDialog.setButton("Cancel", new DialogInterface.OnClickListener() {
	            @Override
				public void onClick(DialogInterface dialog, int which) {
	            	alertDialog.cancel();
	            }    
	        });
	        
	        alertDialog.show();
	        */
        }
	}
	
	@SuppressLint("NewApi")
	public void onInfoButtonClick(View v) {
		PopupMenu popup = new PopupMenu(this, v);
		popup.getMenuInflater().inflate(R.menu.popup, popup.getMenu());
		if(mSelectedFacility >= 0)
			popup.getMenu().getItem(mSelectedFacility).setChecked(true);
		final int lastSelectedFacility = mSelectedFacility;
		popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
		@Override
		public boolean onMenuItemClick(MenuItem item) {
			if(item.getItemId()==R.id.popupOffice)
				mSelectedFacility = 0;
			else if (item.getItemId()==R.id.popupLT)
				mSelectedFacility = 1;
			else if (item.getItemId()==R.id.popupRoom)
				mSelectedFacility = 2;
			else if (item.getItemId()==R.id.popupRestaurant)
				mSelectedFacility = 3;
			else if (item.getItemId()==R.id.popupToilet)
				mSelectedFacility = 4;
			else if (item.getItemId()==R.id.popupLift)
				mSelectedFacility = 5;
			else
				return false;
			/******* Android want constant in switch case only
				switch (item.getItemId()) {
		     case R.id.popupOffice:
		    	 mSelectedFacility = 0;
		    	 break;
		     case R.id.popupLT:  
		    	 mSelectedFacility = 1;
		    	 break;
		     case R.id.popupRoom:  
		    	 mSelectedFacility = 2;
		    	 break;
		     case R.id.popupRestaurant:  
		    	 mSelectedFacility = 3;
		    	 break; 
		     case R.id.popupToilet:  
		    	 mSelectedFacility = 4;
		    	 break;
		     case R.id.popupLift:  
		    	 mSelectedFacility = 5;
		    	 break;
		     default:   
		         return false;  
		     }

			********/
		     if(lastSelectedFacility != mSelectedFacility) {
		    	 if(lastSelectedFacility >= 0)
		    		 setAllFacilitiesInvisible(lastSelectedFacility);
		    	 setAllFacilitiesVisible(mSelectedFacility);
		     }
		     return true;
		}
		});
		popup.show();
	}

	public void setAllFacilitiesVisible(int ftype) {
		List <IGeometry> list = mFacilityHashMap.get(ftype);
		if(list != null) {
			for(int i=0; i<list.size(); i++) {
				String[] gname = list.get(i).getName().split(",");
				IGeometry facility_geometry = list.get(i);
				if ( currentFloor == Integer.valueOf(gname[1]) )
				{
					
					if((int)mSensors.getLocation().distanceTo(facility_geometry.getTranslationLLA()) <= mRadius )
						facility_geometry.setVisible(true);
					else
						facility_geometry.setVisible(false);
				}  
				else
					facility_geometry.setVisible(false);
			}
		}
	}

	public void setAllFacilitiesInvisible(int ftype) {
		List <IGeometry> list = mFacilityHashMap.get(ftype);
		for(int i=0; i<list.size(); i++) {
			IGeometry facility_geometry = list.get(i);
			facility_geometry.setVisible(false);
		}
	}
	
	private void updateStepCounter() {
		String step_instruction;
		if(mSourceNID == mDestinationNID) {
			step_instruction = "YOU ARE AT YOUR DESTINATION";
		}
		else {
		step_instruction = mStepsRemaining + " MORE STEPS";
		
		if(mNextTurn.equals("R"))
			step_instruction += " & TURN RIGHT";
		else if(mNextTurn.equals("L"))
			step_instruction += " & TURN LEFT";
		else if(mNextTurn.equals("U"))
			step_instruction = "TAKE LIFT TO UPPER FLOOR";
		else if(mNextTurn.equals("D"))
			step_instruction = "TAKE LIFT TO LOWER FLOOR";
		else
			step_instruction += " TO THE DESTINATION";
		}
		mStepsTextView.setText(step_instruction);
	}
	
	private void updateVerticalScrollView() {
		if(mPathInstructions.size() != 0) {
			// Path instructions exist
			for(String[] instruction:mPathInstructions) {
				// add distance to the array
				mTotalDistance += Integer.parseInt(instruction[0]);
				if ( instruction[5].equals("U") || instruction[5].equals("D") )
				{	
					if (mPath.contains(",")) {
						String[] path_nodes = mPath.split(",");
						String lift_name = "";
						int tempCurrFloor = -1;
						Cursor cLift = mDatabase.rawQuery("SELECT * FROM Node WHERE _id = " + path_nodes[0], null);
						cLift.moveToFirst();
						tempCurrFloor = cLift.getInt(cLift.getColumnIndex("floor"));
						cLift.close();
						for(int i=1; i<path_nodes.length; i++)
						{
							cLift = mDatabase.rawQuery("SELECT * FROM Node WHERE _id = " + path_nodes[i], null);
							cLift.moveToFirst();
							if ( tempCurrFloor != cLift.getInt(cLift.getColumnIndex("floor")) )
							{
								Cursor c = mDatabase.rawQuery("SELECT * FROM Facility, Node WHERE Facility.nid = Node._id AND Node._id = " + path_nodes[i], null);
								c.moveToFirst();
								lift_name = c.getString(c.getColumnIndex("Facility.fname"));
								c.close();
								break;
							}
							cLift.close();
						}
						cLift.close();
						mPathDistances.add(lift_name);
					}
				}
				else
					mPathDistances.add((int)Math.ceil(Integer.parseInt(instruction[0])/mStepDistance) + " step");
			}
			customListAdapter = new CustomListAdapter(getBaseContext(), R.layout.new_list_item, mPathDistances);
			centerLockVerticalScrollView.setAdapter(getBaseContext(), customListAdapter);
			
			//centerLockHorizontalScrollView.setCenter(0);
			for(int i=0; i<mPathInstructions.size(); i++) {
				centerLockVerticalScrollView.getItem(i).setOnClickListener(itemOnClickListener);
				centerLockVerticalScrollView.getItem(i).setOnLongClickListener(onScrollItemLongClickListener);
				String[] temp = mPathInstructions.get(i);
				String dir_char = (temp[5]);
				int dir_resource;
				if(dir_char.equals("R")) {
					dir_resource = R.drawable.right;
				}
				else if(dir_char.equals("L")) {
					dir_resource = R.drawable.left;
				}
				else if(dir_char.equals("U")) {
					dir_resource = R.drawable.lift;
				}
				else if(dir_char.equals("D")) {
					dir_resource = R.drawable.lift;
				}
				else {
					dir_resource = R.drawable.straight;
				}
				((Holder) centerLockVerticalScrollView.getItem(i).getTag()).getTitle().setBackgroundResource(dir_resource);
			}
		}
		else {
			// Path instruction is empty, meaning that source and destination ids are equal
		}
	}
	
	private void updateGeometryTranslation() {
		for(int i=0; i<6; i++) {
			List <IGeometry> list = mFacilityHashMap.get(i);
				for(int j=0; j<list.size(); j++) {
					IGeometry facility_geometry = list.get(j);
					facility_geometry.setTranslationLLA(facility_geometry.getTranslationLLA());
					String[] gname = facility_geometry.getName().split(",");
					facility_geometry.setName(gname[0] + "," + gname[1]);
				}
		}

		if(mStepsRemaining == 3) {
			String message;
			if(mNextTurn.equals("R")) 
				message = "Turn RIGHT";
			else if(mNextTurn.equals("L"))
				message = "Turn LEFT";
			else
				message = "You have reached your destination";
			mToast.setText(message);
			mToast.show();
			if(mTTSEnabled)
				mTTS.speak(message, TextToSpeech.QUEUE_FLUSH, null);
		}
		
		// update arrow position
		mArrowGeometry.setTranslationLLA(new LLACoordinate(mSensors.getLocation().getLatitude()+mArrowLatitudeAdjust, mSensors.getLocation().getLongitude()+mArrowLongitudeAdjust, 0, 0));
		//mArrowGeometry.setTranslationLLA(new LLACoordinate((mSensors.getLocation().getLongitude()+mArrowLongitudeAdjust), -1*(mSensors.getLocation().getLatitude()+mArrowLatitudeAdjust), 0, 0));
		// automatically go on to the next instruction
		if(mStepsRemaining == 0 && !mNextTurn.equals("U") && !mNextTurn.equals("D")) {
			mArrowGeometry.setVisible(false);
			if(mAutoUpdateEnabled && mCurrIndex < mPathInstructions.size()-1 && !mPathInstructions.get(mCurrIndex)[0].equals("0"))
				centerLockVerticalScrollView.getItem(mCurrIndex + 1).performClick();
		}
		else
			mArrowGeometry.setVisible(true); // if the user has reached the check point, hide the arrow
		
		setAllFacilitiesVisible(mSelectedFacility);
	}
	
	public IGeometry createFacilityGeometry(final String fname, int ftype, final double latitude, final double longitude) {
		LLACoordinate facility_location = new LLACoordinate(latitude, longitude, 0, 0);
		IGeometry facility_geometry = metaioSDK.createGeometryFromImage(createBillboardTexture(fname, ftype), true);
		facility_geometry.setTranslationLLA(facility_location);
		facility_geometry.setVisible(false);
		facility_geometry.setLLALimitsEnabled(true);
		facility_geometry.setScale(15);

		mRadar.add(facility_geometry);
		MetaioDebug.log("Drawn Facility: " + fname);
		// initially, hide facility geometry
		return facility_geometry;
	}
	
	public void createFacilityGeometries() throws IOException {
		  // populate hash map
		  mLTHashMap.put("1106", "LT-A");
		  mLTHashMap.put("1108", "LT-B");
		  mLTHashMap.put("1110", "LT-C");
		  mLTHashMap.put("1112", "LT-D");
		  mLTHashMap.put("1114", "LT-E");
		  mLTHashMap.put("1406", "LT-F");
		  mLTHashMap.put("1507", "LT-G");
		  mLTHashMap.put("1514", "LT-H");
		  
		mFacilityHashMap = new HashMap<Integer, List<IGeometry>>();
		// Read facility table for AR billboards
		for(int i=0; i<6; i++) {
			Cursor c = mDatabase.rawQuery("SELECT * FROM Facility WHERE ftype = " + i, null);
	        c.moveToFirst();
			int count = c.getCount();
	        List <IGeometry> mList = new ArrayList<IGeometry>();
			for (int j=0; j<count; j++) {
	        	String fname = c.getString(c.getColumnIndex("fname"));
	        	
	        	int ftype = c.getInt(c.getColumnIndex("ftype"));
	        	
	        	int nid = c.getInt(c.getColumnIndex("nid"));
	        	Cursor node_cursor = mDatabase.rawQuery("SELECT * FROM Node WHERE _id=" + nid, null);
	        	node_cursor.moveToFirst();
	        	if(node_cursor.getCount() != 0) {
		        	double latitude = node_cursor.getDouble(node_cursor.getColumnIndex("latitude"));
		        	double longitude = node_cursor.getDouble(node_cursor.getColumnIndex("longitude"));
		        	int floor = node_cursor.getInt(node_cursor.getColumnIndex("floor"));
		        	IGeometry facility_geometry = createFacilityGeometry(fname, ftype, latitude, longitude);
		        	facility_geometry.setName(fname + "," + floor);
		        	// add all facilities with type i into mList
		        	mList.add(facility_geometry);
		        }
	        	c.moveToNext();
	        }
			// put all facilities with type i into mHashMap
			mFacilityHashMap.put(i, mList);
		}
		
		mArrowGeometry = metaioSDK.createGeometryFromImage(AssetsManager.getAssetPath("metaio/green_arrow.png"), true);
		mArrowGeometry.setName("arrow");
		mArrowGeometry.setLLALimitsEnabled(true);
		mArrowGeometry.setScale(15);
		mRadar.add(mArrowGeometry);
		mRadar.setObjectTexture(mArrowGeometry, AssetsManager.getAssetPath("metaio/red.png"));
		mArrowGeometry.setVisible(true);
	}
	
	private void getPathInstructions() {
		
		Cursor tempc = mDatabase.rawQuery("SELECT floor FROM Node WHERE _id = " + mSourceNID, null);
		tempc.moveToFirst();
		sourceFloor = tempc.getDouble(tempc.getColumnIndex("floor"));
		currentFloor = (int) sourceFloor;
        tempc = mDatabase.rawQuery("SELECT floor FROM Node WHERE _id = " + mDestinationNID, null);
		tempc.moveToFirst();
		targetFloor = tempc.getDouble(tempc.getColumnIndex("floor"));
		tempc.close();
		
		if (mPath.contains(",")) {
			
			// Read node table for path navigation
			String[] path_nodes = mPath.split(",");
			Log.d("debb22", "mPath " + mPath);
			
			int tempCurrFloor = -1;
			int liftingLiftPosi = -999999;
			Cursor cLift = mDatabase.rawQuery("SELECT floor FROM Node WHERE _id = " + path_nodes[0], null);
			cLift.moveToFirst();
			tempCurrFloor = cLift.getInt(cLift.getColumnIndex("floor"));
			for(int i=1; i<path_nodes.length; i++)
			{
				cLift = mDatabase.rawQuery("SELECT floor FROM Node WHERE _id = " + path_nodes[i], null);
				cLift.moveToFirst();
				if ( tempCurrFloor != cLift.getInt(cLift.getColumnIndex("floor")) )
				{
					liftingLiftPosi = i-1;
					break;
				}
				cLift.moveToNext();
			}
			cLift.close();
			
			LLACoordinate prev_prev_coordinate = null;
			LLACoordinate prev_coordinate = null;
			LLACoordinate curr_coordinate = null;
			int temp_index = 0;
			
			String upOrDown;
			if (sourceFloor < targetFloor)
				upOrDown = "U";
			else
				upOrDown = "D";
			
			int accuDistance = 0;
			
			LLACoordinate pin_coordinate = null;
			 
			for(int i=0; i<path_nodes.length; i++) {
				Log.i("LLA", "In path node " + path_nodes[i]);
				Cursor c = mDatabase.rawQuery("SELECT * FROM Node WHERE _id = " + path_nodes[i], null);
		        c.moveToFirst();
	        	if(c.getCount() != 0) {
		        	double latitude = c.getDouble(c.getColumnIndex("latitude"));
		        	double longitude = c.getDouble(c.getColumnIndex("longitude"));
		        	curr_coordinate = new LLACoordinate(latitude, longitude, 0, 0);
				    c.close();
		        	if (i == 0) {
		        		pin_coordinate = curr_coordinate;
		        	}
		        	
		        	if ( i == liftingLiftPosi + 2) {
			        	String[] temp = {"0",
	    		        	String.valueOf(prev_coordinate.getLatitude()),String.valueOf(prev_coordinate.getLongitude()),
	    		        	String.valueOf(curr_coordinate.getLatitude()),String.valueOf(curr_coordinate.getLongitude()),upOrDown,
	    		        	String.valueOf(prev_coordinate.getLatitude()),String.valueOf(prev_coordinate.getLongitude())};
			        	mPathInstructions.add(temp);
	        			mNodeConversionTable.add(temp_index);
	        			temp_index = i-1;
	        			prev_prev_coordinate = prev_coordinate;
	        			prev_coordinate = curr_coordinate;
		        	} else if ( i == liftingLiftPosi + 1 ) {
		        		prev_prev_coordinate = prev_coordinate;
	    	        	prev_coordinate = curr_coordinate;
	    	        	pin_coordinate = curr_coordinate;
		        	} else if(prev_prev_coordinate!=null&&prev_coordinate!=null) {
		        		Log.i("LLA", "After path node " + path_nodes[i]);
		        		Cursor c2 = mDatabase.rawQuery("SELECT * FROM Edge WHERE nid_start = " + path_nodes[i-2] + " AND nid_end = " + path_nodes[i-1], null);
		        		c2.moveToFirst();
				        int length = c2.getInt(c2.getColumnIndex("length"));
		        		// if all three coordinates have been passed as parameters
		        		double angle = calculateAngle(prev_prev_coordinate, prev_coordinate, curr_coordinate);
		        		c2.close();
		        		
		        		// no turn; straight line
		        		if(angle == 180||angle == -180) {
		        			prev_prev_coordinate = prev_coordinate;
		    	        	prev_coordinate = curr_coordinate;
		    	        	accuDistance += length;
		        		}
		        		// right turn
		        		else if(angle<0) {
		        			String[] temp = {String.valueOf(length + accuDistance),
		    		        		String.valueOf(prev_coordinate.getLatitude()),String.valueOf(prev_coordinate.getLongitude()),
		    		        		String.valueOf(curr_coordinate.getLatitude()),String.valueOf(curr_coordinate.getLongitude()),"R",
		    		        		String.valueOf(pin_coordinate.getLatitude()),String.valueOf(pin_coordinate.getLongitude())};
		        			mPathInstructions.add(temp);
		        			mNodeConversionTable.add(temp_index);
		    	        	temp_index = i-1;
		        			prev_prev_coordinate = prev_coordinate;
		    	        	prev_coordinate = curr_coordinate;
		    	        	pin_coordinate = prev_coordinate;
		    	        	accuDistance = 0;
		        		}
		        		// left turn
		        		else if(angle>0) {
		        			String[] temp = {String.valueOf(length + accuDistance),
		    		        		String.valueOf(prev_coordinate.getLatitude()),String.valueOf(prev_coordinate.getLongitude()),
		    		        		String.valueOf(curr_coordinate.getLatitude()),String.valueOf(curr_coordinate.getLongitude()),"L",
		    		        		String.valueOf(pin_coordinate.getLatitude()),String.valueOf(pin_coordinate.getLongitude())};
		        			mPathInstructions.add(temp);
		        			mNodeConversionTable.add(temp_index);
		        			temp_index = i-1;
		        			prev_prev_coordinate = prev_coordinate;
		    	        	prev_coordinate = curr_coordinate;
		    	        	pin_coordinate = prev_coordinate;
		    	        	accuDistance = 0;
		        		}
		        	} else {
		        		prev_prev_coordinate = prev_coordinate;
	    	        	prev_coordinate = curr_coordinate;
		        	}
		        }
			}
			
			Cursor c2 = mDatabase.rawQuery("SELECT * FROM Edge WHERE nid_start = " + path_nodes[path_nodes.length-1] + " AND nid_end = " + path_nodes[path_nodes.length-2] , null);
    		c2.moveToFirst();
	        int length = c2.getInt(c2.getColumnIndex("length"));
	        c2.close();
	        
			if(prev_prev_coordinate!=null&&prev_coordinate!=null) {
				String[] temp = {String.valueOf(length + accuDistance),
		        		String.valueOf(prev_coordinate.getLatitude()),String.valueOf(prev_coordinate.getLongitude()),
		        		String.valueOf(curr_coordinate.getLatitude()),String.valueOf(curr_coordinate.getLongitude()),"S",
		        		String.valueOf(pin_coordinate.getLatitude()),String.valueOf(pin_coordinate.getLongitude())};
				mPathInstructions.add(temp);
				mNodeConversionTable.add(temp_index);
			}
			else if(prev_coordinate!=null&&curr_coordinate!=null) {
				String[] temp = {String.valueOf(length + accuDistance),
		        		String.valueOf(prev_coordinate.getLatitude()),String.valueOf(prev_coordinate.getLongitude()),
		        		String.valueOf(curr_coordinate.getLatitude()),String.valueOf(prev_coordinate.getLongitude()),"S",
		        		String.valueOf(pin_coordinate.getLatitude()),String.valueOf(pin_coordinate.getLongitude())};
				mPathInstructions.add(temp);
				mNodeConversionTable.add(temp_index);
			}
		}
	}	/**
	
	 * Return angle between three coordinates where c2 is the pivot 
	 * Positive angle for left turn and negative angle for right turn
	 */
	private double calculateAngle(LLACoordinate c1, LLACoordinate c2, LLACoordinate c3) {
		LLACoordinate v1 = new LLACoordinate(c1.getLongitude()-c2.getLongitude(), c1.getLatitude()-c2.getLatitude(),0,0);
		Log.i("LLA", "LLA v1 is " + v1);
		LLACoordinate v2 = new LLACoordinate(c3.getLongitude()-c2.getLongitude(), c3.getLatitude()-c2.getLatitude(),0,0);
		Log.i("LLA", "LLA v2 is " + v2);
		return -(180/Math.PI)*Math.atan2(v1.getLongitude()*v2.getLatitude() - v1.getLatitude()*v2.getLongitude(), v1.getLongitude()*v2.getLongitude()+v1.getLatitude()*v2.getLatitude());
	}
	
	final class MetaioSDKCallbackHandler extends IMetaioSDKCallback 
	{
		@Override
		public void onSDKReady() 
		{
			// show GUI
			runOnUiThread(new Runnable() 
			{
				@Override
				public void run() 
				{
					mGUIView.setVisibility(View.VISIBLE);
					// initially, the first instruction is on focus
					if(mSourceNID != mDestinationNID) {
						centerLockVerticalScrollView.getItem(0).performClick();
					}
					else {
						// update step counter
						updateStepCounter();
					}
					mIsFirstRun = false;
				}
			});
		}
		
		@Override
		public void onTrackingEvent(TrackingValuesVector trackingValues) 
		{
			if (trackingValues.size() > 0)
			{
				// if tracking is lost, request visual search
				if (trackingValues.get(0).getState() == ETRACKING_STATE.ETS_LOST)
				{
					// make the billboards visible
					if(mMovieModel != null)
						mMovieModel.setVisible(false);
					mRadar.setVisible(true);
					mArrowGeometry.setVisible(true);
					if(mSelectedFacility != -1)
						setAllFacilitiesVisible(mSelectedFacility);
					
					boolean result = metaioSDK.setTrackingConfiguration("GPS");  
					MetaioDebug.log("Tracking data loaded: " + result);
					
					MetaioDebug.log("Requesting a new visual search because tracking is lost...");
					mCVSrequest=true;
				}
			}
		}
	}
	
	final class VisualSearchCallbackHandler extends IVisualSearchCallback 
	{
		@Override
		public void onVisualSearchResult(VisualSearchResponseVector response, int errorCode)
		{
			MetaioDebug.log("onVisualSearchResult: "+errorCode+", "+response.size());
			if (errorCode == 0 && response.size() > 0) 
			{
				// hide the billboards
				mRadar.setVisible(false);
				mArrowGeometry.setVisible(false);
				if(mSelectedFacility != -1)
					setAllFacilitiesInvisible(mSelectedFacility);
				
				// set the searched image as a tracking target
				MetaioDebug.log("Loading tracking configuration...");
				boolean result = metaioSDK.setTrackingConfiguration(response.get(0).getTrackingConfiguration(), false);
				MetaioDebug.log("Tracking configuration loaded: "+result);
				
				// remove the file extension
				final String name = response.get(0).getTrackingConfigurationName().replaceFirst("[.][^.]+$", "");
				String moviePath = null;
				
				if(name.equals("seng")) {
					moviePath = AssetsManager.getAssetPath("movies/seng.3g2");
				}
				else if(name.equals("sbm")) {
					moviePath = AssetsManager.getAssetPath("movies/sbm.3g2");
				}
				else if(name.equals("ssci")) {
					moviePath = AssetsManager.getAssetPath("movies/ssci.3g2");
				}
				else if(name.equals("shss")) {
					moviePath = AssetsManager.getAssetPath("movies/shss.3g2");
				}
				
				if (moviePath != null)
				{
					if(mMovieModel != null)
						metaioSDK.unloadGeometry(mMovieModel); //if there already exists any video loaded, unload first 
			
//					if(mCVSModel != null)
//						mCVSModel.setVisible(false); //if the VS has been on for some time, make sure search result from other is not visible 

					mMovieModel = metaioSDK.createGeometryFromMovie(moviePath, false, true);
					mMovieModel.setName("movie");
					
					if (mMovieModel != null)
					{
						mMovieModel.setScale(5.0f);
						//mModel.setRotation(new Rotation(0f, 0f, (float)-Math.PI/2));
						MetaioDebug.log("Loaded geometry "+moviePath);
						mMovieModel.startMovieTexture(true);
					}
					else {
						MetaioDebug.log(Log.ERROR, "Error loading geometry: "+moviePath);
					}
				}
				
				/**********
				// load an image geometry to display the result on the pattern
				final String texturePath = AssetsManager.getAssetPath("metaio/cvsbg.png");
				if (texturePath != null) 
				{
					// remove the file extension
					final String name = response.get(0).getTrackingConfigurationName().replaceFirst("[.][^.]+$", "");
					
					// create a billboard texture that highlights the file name of the searched image
					final String imagePath = createTexture(name, texturePath);
					
					if (imagePath != null)
					{
						if (mCVSModel == null)
						{
							// create new geometry
							mCVSModel = metaioSDK.createGeometryFromImage(imagePath);
							mCVSModel.setScale(1.5f);
							MetaioDebug.log("The image has been loaded successfully");
						}
						else
						{
							// update texture with new image
							mCVSModel.setTexture(imagePath);
						}
					}
					else
					{
						MetaioDebug.log(Log.ERROR, "Error creating image texture");
					}
				}
				**********/

			} 
			else 
			{
				if (errorCode > 0)
					MetaioDebug.log(Log.ERROR, "Visual search error: "+errorCode);
				
				// if visual search didn't succeed, request another round
				MetaioDebug.log("Requesting new visual search because search failed...");
				metaioSDK.requestVisualSearch(databaseID, true);
			}
		}

		@Override
		public void onVisualSearchStatusChanged(EVISUAL_SEARCH_STATE state) 
		{
			MetaioDebug.log("The current visual search state is: " + state);
		}

	}

	public void onCVSButtonClick(View v) {
		boolean on = ((ToggleButton) v).isChecked();
		if (on) {
	        // enable CVS
			if(checkNetworkConnection()) {
				mVSCallback = new VisualSearchCallbackHandler();
				metaioSDK.registerVisualSearchCallback(mVSCallback);
				mPaint = new Paint();
				mCVSrequest = true;
				String message = "Visual search is enabled";
				mToast.setText(message);
		        mToast.show();
		        if(mTTSEnabled)
		        	mTTS.speak(message, TextToSpeech.QUEUE_FLUSH, null);
			}
			else {
				String message = "No internet connection\nTurn on WiFi or mobile data";
				mToast.setText(message);
		        mToast.show();
		        if(mTTSEnabled)
		        	mTTS.speak(message, TextToSpeech.QUEUE_FLUSH, null);
		        ((ToggleButton) v).setChecked(false);
			}
	    } else {
	        // disable CVS
	    	metaioSDK.registerVisualSearchCallback(null);
			mVSCallback.delete();
			mVSCallback = null;
	    	mCVSrequest = false;
	    	String message = "Visual search is disabled";
	    	mToast.setText(message);
	        mToast.show();
	        if(mTTSEnabled)
	        	mTTS.speak(message, TextToSpeech.QUEUE_FLUSH, null);
	    }
	}
	
	private boolean checkNetworkConnection() {
	    boolean haveConnectedWifi = false;
	    boolean haveConnectedMobile = false;
	    ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
	    NetworkInfo[] netInfo = cm.getAllNetworkInfo();
	    for (NetworkInfo ni : netInfo) {
	        if (ni.getTypeName().equalsIgnoreCase("WIFI"))
	            if (ni.isConnected()) {
	                haveConnectedWifi = true;
	            }
	        if (ni.getTypeName().equalsIgnoreCase("MOBILE"))
	            if (ni.isConnected()) {
	                haveConnectedMobile = true;
	            }
	    }
	    return haveConnectedWifi || haveConnectedMobile;
	}
	
	public void onStepsTextViewClick(View v) {
		retrievePreferences();
		if(mPedometerDialog == null) {
			mPedometerDialog = new PedometerDialog(this);
		}
		mPedometerDialog.show();
		mPedometerDialog.setSensitivitySeekBar(mSensitivity);
		mPedometerDialog.setHeightSeekBar(mUserHeight);
		mPedometerDialog.setAutoUpdateCheckBox(mAutoUpdateEnabled);
		mPedometerDialog.setTTSCheckBox(mTTSEnabled);
		mPedometerDialog.setDialogResult(new onDialogResult() {
			@Override
			public void finish(int sensitivity, int height, boolean auto_update, boolean tts) {
				// executed when the dialog box is closed with Reset or Save buttons
				// update with the new values
				mSensitivity = sensitivity;
				mUserHeight = height;
				mAutoUpdateEnabled = auto_update;
				mTTSEnabled = tts;
				savePreferences(mSensitivity, mUserHeight, mAutoUpdateEnabled, mTTSEnabled);
				// make the changes effective by changing the sensitivity and step distance
				mStepDetector.setSensitivity(mSensitivity);

				double remaining_distance = mStepsRemaining * mStepDistance;
				mStepDistance = (double)(100 + mUserHeight)/100 * STEP_LENGTH_PER_METER;
				
				mStepsRemaining = (int) Math.ceil(remaining_distance / mStepDistance);
				updateStepCounter();
			}
		});
	}
	
	private void retrievePreferences() {
		// retrieve prference data
		mPrefs = this.getPreferences(MODE_PRIVATE);
		mSensitivity = mPrefs.getInt(KEY_PEDOMETER_SENSITIVITY, 13);
		mUserHeight = mPrefs.getInt(KEY_PEDOMETER_HEIGHT, 70);
		mAutoUpdateEnabled = mPrefs.getBoolean(KEY_PEDOMETER_AUTO_UPDATE, true);
		mTTSEnabled = mPrefs.getBoolean(KEY_PEDOMETER_TTS, true);
		mStepDistance = (double)(100 + mUserHeight)/100 * STEP_LENGTH_PER_METER;
		mStepDetector.setSensitivity(mSensitivity);
	}
	
	private void savePreferences(int sensitivity, int height, boolean auto_update, boolean tts) {
		SharedPreferences.Editor editor = mPrefs.edit();
		editor.putInt(KEY_PEDOMETER_SENSITIVITY, sensitivity);
		editor.putInt(KEY_PEDOMETER_HEIGHT, height);
		editor.putBoolean(KEY_PEDOMETER_AUTO_UPDATE, auto_update);
		editor.putBoolean(KEY_PEDOMETER_TTS, tts);
		editor.commit();
		// notify the user that preference has been changed
		mToast.setText("Saved");
		mToast.show();
	}
	
	OnClickListener itemOnClickListener = new OnClickListener() {
		@Override
		public void onClick(View v) {
			mCurrIndex = v.getId();
			centerLockVerticalScrollView.setCenter(mCurrIndex);
			String[] instruction = mPathInstructions.get(mCurrIndex);
			mStepsRemaining = (int)Math.ceil(Integer.parseInt(instruction[0]) / mStepDistance);
			mNextTurn = instruction[5];
			if(mNextTurn.equals("U") || mNextTurn.equals("D")) {
				mCurrentLatitude = Double.valueOf(instruction[1]);
				mCurrentLongitude = Double.valueOf(instruction[2]);
			} else {
				mCurrentLatitude = Double.valueOf(instruction[6]);
				mCurrentLongitude = Double.valueOf(instruction[7]);
			}
			mSensors.setManualLocation(new LLACoordinate(mCurrentLatitude, mCurrentLongitude, 0, 0));
    		mNextTargetLocation = new LLACoordinate(Double.valueOf(instruction[1]), Double.valueOf(instruction[2]), 0, 0);
    		// update arrow geometry
    		mArrowLatitudeAdjust = mNextTargetLocation.getLatitude() - mCurrentLatitude;
    		mArrowLongitudeAdjust = mNextTargetLocation.getLongitude() - mCurrentLongitude;
    		double magnitude = Math.sqrt(Math.pow(mArrowLatitudeAdjust, 2)+Math.pow(mArrowLongitudeAdjust, 2)) * 30000;
    		mArrowLatitudeAdjust /= magnitude;
    		mArrowLongitudeAdjust /= magnitude;
    		
    		
    		
    		// update the step counts
    		mStepsToNextPoint = (int)Math.ceil(Integer.parseInt(instruction[0]) / mStepDistance);
    		updateStepCounter();
    		
			// inform the user which way to go
			String message = (mCurrIndex + 1) + ") Walk " + (int)Math.ceil(Integer.parseInt(instruction[0]) / mStepDistance) + " Steps ";
	        // Find out the direction
	        if(mNextTurn.equals("R"))
	        	message += "and turn RIGHT";
	        else if(mNextTurn.equals("L"))
	        	message += "and turn LEFT";
	        else if(mNextTurn.equals("U"))
	        {
	        	message = "Go to " + (int)targetFloor + " Floor";
	        	currentFloor = (int)targetFloor;
	        }
	        else if(mNextTurn.equals("D"))
	        {
	        	message = "Go to " + (int)targetFloor + " Floor";
	        	currentFloor = (int)targetFloor;
	        }
	        else
		        message += "to the destination";
	        // Display the message
	        mToast.setText(message);
	        mToast.show();
	        

	        if(mNextTurn.equals("U") || mNextTurn.equals("D"))
	        {
	    		TestDialog tDialog = new TestDialog(myAct, centerLockVerticalScrollView, mCurrIndex, R.style.Theme_CustomDialog);
				TextView textview = (TextView) tDialog.findViewById(R.id.msgText);
				textview.setText("Swipe Me after lift to " + (int)targetFloor + " floor");
				textview.setTextSize(24);
				tDialog.setCancelable(false);
				tDialog.getWindow().getAttributes().windowAnimations = R.style.SlideLeftDialogAnimation;
	    		tDialog.show();
	        	if(mTTSEnabled)
		        	mTTS.speak(message, TextToSpeech.QUEUE_FLUSH, null);
	        }
	        else
	        {
	        	if(mTTSEnabled)
		        	mTTS.speak(message.substring(message.indexOf("Walk")), TextToSpeech.QUEUE_FLUSH, null);
	        }
	        
	        updateGeometryTranslation();
		}
	};
	
	OnLongClickListener onScrollItemLongClickListener = new OnLongClickListener() {
		@Override
		public boolean onLongClick(View v) {
			Builder alertDialog = new AlertDialog.Builder(v.getContext());
	        
	        String dist_str = (String)((Holder)v.getTag()).getTitle().getText();
	        int dist_int = Integer.parseInt(dist_str.substring(0, dist_str.length()-1));
	        String dialog_title = (v.getId()+1) + ") Walk " + (int) Math.ceil((dist_int / mStepDistance)) + " Steps ";
	        String[] temp = mPathInstructions.get(v.getId());
	        String dir_char = (temp[5]);
	        
	        // Find out the direction
	        if(dir_char.equals("R"))
	        	dialog_title += "& Turn RIGHT";
	        else if(dir_char.equals("L"))
	        	dialog_title += "& Turn LEFT";
	        else
		        dialog_title += "& REACHED";
	        
	        // Calculate distance travelled
	        int travelled_distance = 0;
	        for(int i=0;i<(v.getId());i++) {
	        	String[] instruction = mPathInstructions.get(i);
	        	travelled_distance += Integer.parseInt(instruction[0]);
	        }
	        	
	        // Setting Dialog Title
	        alertDialog.setTitle(dialog_title);
	        // Setting Dialog Message
	        String message = "Total Distance = "+mTotalDistance+"m\nTravelled Distance = "+
	        travelled_distance+"m\nRemaining Distance = "+(mTotalDistance-travelled_distance)+"m";
	        alertDialog.setMessage(message);
	        // Showing Alert Message
	        alertDialog.create().show();
			return true;
		}
	};
	
	OnClickListener onScrollItemClickListener = new OnClickListener() {
		@Override
		public void onClick(View v) {
			if (v.getId() == R.id.btnPrev) {
		        if (mCurrIndex > 0) {
		        	centerLockVerticalScrollView.getItem(mCurrIndex - 1).performClick();
		        }
		    } else if (v.getId() == R.id.btnNext) {
		        if (mCurrIndex < mPathInstructions.size()-1) {
		        	centerLockVerticalScrollView.getItem(mCurrIndex + 1).performClick();
		        }
		    }
		}
	};
	
	/**
	 * Step detector inner class inside location based activity.
	 * Detects steps and notifies all listeners (that implement StepListener).
	 */
	public class StepDetector implements SensorEventListener
	{
	    private float   mLimit = 10;
	    private float   mLastValues[] = new float[3*2];
	    private float   mScale[] = new float[2];
	    private float   mYOffset;

	    private float   mLastDirections[] = new float[3*2];
	    private float   mLastExtremes[][] = { new float[3*2], new float[3*2] };
	    private float   mLastDiff[] = new float[3*2];
	    private int     mLastMatch = -1;
	    
	    private float   mGravity[];
	    private float   mGeomagnetic[];
	    private float 	mRotationMatrixA[] = new float[16];
	    private float 	mRotationMatrixB[] = new float[16];
	    
	    public StepDetector() {
	        int h = 480; // TODO: remove this constant
	        mYOffset = h * 0.5f;
	        mScale[0] = - (h * 0.5f * (1.0f / (SensorManager.STANDARD_GRAVITY * 2)));
	        mScale[1] = - (h * 0.5f * (1.0f / (SensorManager.MAGNETIC_FIELD_EARTH_MAX)));
	    }
	    
	    public void setSensitivity(float sensitivity) {
	        mLimit = (float)(sensitivity + 1.5); // 1.97  2.96  4.44  6.66  10.00  15.00  22.50  33.75  50.62
	    }

	    //public void onSensorChanged(int sensor, float[] values) {
	    public void onSensorChanged(SensorEvent event) {
	        synchronized (this) {
	        	Sensor sensor = event.sensor;
	        	if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) return;
	            if (sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) mGeomagnetic =  event.values.clone();
	        	int j = (sensor.getType() == Sensor.TYPE_ACCELEROMETER) ? 1 : 0;
                if (j == 1) {
                		mGravity = event.values.clone();
	                    float vSum = 0;
	                    for (int i=0 ; i<3 ; i++) {
	                        final float v = mYOffset + mGravity[i] * mScale[j];
	                        vSum += v;
	                    }
	                    int k = 0;
	                    float v = vSum / 3;
	                
	                float direction = (v > mLastValues[k] ? 1 : (v < mLastValues[k] ? -1 : 0));
	                if (direction == - mLastDirections[k]) {
	                    // Direction changed
	                    int extType = (direction > 0 ? 0 : 1); // minumum or maximum?
	                    mLastExtremes[extType][k] = mLastValues[k];
	                    float diff = Math.abs(mLastExtremes[extType][k] - mLastExtremes[1 - extType][k]);
	
	                    if (diff > mLimit) {
	                        
	                        boolean isAlmostAsLargeAsPrevious = diff > (mLastDiff[k]*2/3);
	                        boolean isPreviousLargeEnough = mLastDiff[k] > (diff/3);
	                        boolean isNotContra = (mLastMatch != 1 - extType);
	                        
	                        if (isAlmostAsLargeAsPrevious && isPreviousLargeEnough && isNotContra) {
	                        	float direction_degree = 0;
	            	            if (mGravity != null && mGeomagnetic != null) {
	            	            	SensorManager.getRotationMatrix(mRotationMatrixA, null, mGravity, mGeomagnetic);
	            	            	SensorManager.remapCoordinateSystem(mRotationMatrixA, SensorManager.AXIS_X, SensorManager.AXIS_Z, mRotationMatrixB);
	            	            	float[] dv = new float[3]; 
	            	                SensorManager.getOrientation(mRotationMatrixB, dv);
	            	                // convert radians to degrees
	            	                //dv[0] += Math.PI/4;
	            	                direction_degree = (float)Math.toDegrees(dv[0]);
	            	                if (direction_degree < 0.0f) {
	            	                    direction_degree += 360.0f;
	            	                }
	            	            }
	            	            boolean correct_direction = false;
	            	            if(mArrowLongitudeAdjust == 0) {
	            	            	if(mArrowLatitudeAdjust > 0) // arrow towards the north
	            	            		correct_direction = (direction_degree > 270 || direction_degree < 90)? true:false;
	            	            	else // arrow towards the south
	            	            		correct_direction = (direction_degree > 90 && direction_degree < 270)? true:false;
	            	            }
	            	            else if(mArrowLatitudeAdjust == 0) {
	            	            	if(mArrowLongitudeAdjust > 0 ) // arrow towards the north
	            	            		correct_direction = (direction_degree > 0 && direction_degree < 180)? true:false;
	            	            	else // arrow towards the south
	            	            		correct_direction = (direction_degree > 180 && direction_degree < 360)? true:false;
	            	            }
	                        	
	                        	if(correct_direction) {
		                            // when step is detected, decrement the remaining steps
		                            if(mStepsRemaining > 0)
		                            	mStepsRemaining--;
		                            
		                            // avoid step counting when variables are not yet initialized
		                            if(!mIsFirstRun) {
		                            	updateStepCounter();
		                            	// for every remaining step, update my current location
		                                if(mSourceNID != mDestinationNID) {
		                                	double percentage_travelled = (1 - ((double)mStepsRemaining / mStepsToNextPoint));
		                                	double current_latitude = mCurrentLatitude + (mNextTargetLocation.getLatitude() - mCurrentLatitude) * percentage_travelled;
		                                	double current_longitude = mCurrentLongitude + (mNextTargetLocation.getLongitude() - mCurrentLongitude) * percentage_travelled;
		                                	mSensors.setManualLocation(new LLACoordinate(current_latitude, current_longitude, 0, 0));
		                                	updateGeometryTranslation();
		                                }
		                            }
		                            
		                            mLastMatch = extType;
	                        	}
	                        	else {
	                        		// display a toast message if the step was detected facing a wrong direction
	            	                String message = "Wrong direction. Walk towards the arrow";
	                        		mToast.setText(message);
	            	                mToast.show();
	            	                if(mTTSEnabled && !mTTS.isSpeaking())
	            	                	mTTS.speak(message, TextToSpeech.QUEUE_FLUSH, null);
	                        	}
	                        }
	                        else {
	                            mLastMatch = -1;
	                        }
	                    }
	                    mLastDiff[k] = diff;
	                }
	                mLastDirections[k] = direction;
	                mLastValues[k] = v;
	            }
	        }
	    }
	    
	    public void onAccuracyChanged(Sensor sensor, int accuracy) {
	        // TODO Auto-generated method stub
	    }
	}
	
	private void callFacilityDialog(String nid){
		if (nid.equals("F")||nid.equals("M")||nid.contains("LIFT"))
			return;
		  String weekDay;
		  SimpleDateFormat dayFormat = new SimpleDateFormat("EEE", Locale.US);

		  Calendar calendar = Calendar.getInstance();
		  weekDay = dayFormat.format(calendar.getTime());
		  weekDay = weekDay.substring(0, 2);
		  
		  // Cursor c = mDatabase.rawQuery("SELECT * FROM Course WHERE Room = '" + nid + "' AND Week = '" + weekDay + "'", null);
		  Cursor c = mDatabase.rawQuery("SELECT * FROM Course WHERE Room = '" + nid + "' AND Week = 'Fr'", null);
		  c.moveToFirst();
	      int count = c.getCount();
		  if ( count == 0)
		  {
			  final AlertDialog alertDialog = new AlertDialog.Builder(this).create();
			  alertDialog.setTitle(nid);
			  alertDialog.setMessage("No course are found at " + nid + " today");
			  alertDialog.show();
			  return;
		  }
		  Log.i("LLA", "Calling fDialog "+ nid);
		  mFacilityDialog = new FacilityDialog(this);
		  mFacilityDialog.show();
		  for(int i = 0; i < count; i++) {
			  int sTime = Integer.parseInt(c.getString(c.getColumnIndex("StartTime")));
			  int eTime = Integer.parseInt(c.getString(c.getColumnIndex("EndTime")));
			  int sHour = sTime/100;
			  int sMin = sTime%100;
			  int eHour = eTime/100;
			  int eMin = eTime%100;
			  int hDiff = eHour - sHour;
			  hDiff *= 2;
			  int amend = 0;
			  if ( sMin==0 && eMin== 50 )
				  amend = 2;
			  else if ( sMin==0 && eMin== 20)
				  amend = 1;
			  else if ( sMin==30 && eMin==50 )
				  amend = 1;
			  else if ( sMin==30 && eMin==20 )
				  amend = 0;
			  hDiff += amend;
			  sHour *= 2;
			  if ( sMin==30 )
				  sHour++;
			  String r_id = "timeavailable";
			  for(int j = 0; j < hDiff; j++) {
				  int r = getResources().getIdentifier(r_id + String.valueOf(sHour), "id", getPackageName());
				  mFacilityDialog.setTimeAva(r, c.getString(c.getColumnIndex("CourseCode")) + " " + c.getString(c.getColumnIndex("Section")));
				  sHour++;
			  }
			  c.moveToNext();
		  }
		  c.close();
		  
		  dayFormat = new SimpleDateFormat("EEEE", Locale.US);
		  weekDay = dayFormat.format(calendar.getTime());
		  mFacilityDialog.setFDialogTitle(nid + " on " + weekDay);
		  
	  }
}
