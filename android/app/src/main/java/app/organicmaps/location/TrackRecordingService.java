package app.organicmaps.location;

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.POST_NOTIFICATIONS;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.app.ForegroundServiceStartNotAllowedException;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationChannelCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import app.organicmaps.MwmActivity;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.sdk.location.LocationHelper;
import app.organicmaps.sdk.location.LocationListener;
import app.organicmaps.sdk.location.TrackRecorder;
import app.organicmaps.sdk.util.LocationUtils;
import app.organicmaps.sdk.util.log.Logger;

import android.content.SharedPreferences;
import com.google.gson.JsonObject;
import app.organicmaps.api.SupabaseApi;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class TrackRecordingService extends Service implements LocationListener
{
  public static final String TRACK_REC_CHANNEL_ID = "TRACK RECORDING";
  public static final String STOP_TRACK_RECORDING = "STOP_TRACK_RECORDING";
  public static final int TRACK_REC_NOTIFICATION_ID = 54321;
  private NotificationCompat.Builder mNotificationBuilder;
  private static final String TAG = TrackRecordingService.class.getSimpleName();
  private boolean mWarningNotification = false;
  private NotificationCompat.Builder mWarningBuilder;
  private PendingIntent mPendingIntent;
  private PendingIntent mExitPendingIntent;

  @Nullable
  @Override
  public IBinder onBind(Intent intent)
  {
    return null;
  }

  @RequiresPermission(value = ACCESS_FINE_LOCATION)
  public static void startForegroundService(@NonNull Context context)
  {
    if (!TrackRecorder.nativeIsTrackRecordingEnabled())
      TrackRecorder.nativeStartTrackRecording();
    MwmApplication.from(context).getLocationHelper().restartWithNewMode();
    ContextCompat.startForegroundService(context, new Intent(context, TrackRecordingService.class));
  }

  public static void createNotificationChannel(@NonNull Context context)
  {
    final NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
    final NotificationChannelCompat channel =
        new NotificationChannelCompat.Builder(TRACK_REC_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(context.getString(R.string.track_recording))
            .setLightsEnabled(false)
            .setVibrationEnabled(false)
            .build();
    notificationManager.createNotificationChannel(channel);
  }

  private PendingIntent getPendingIntent(@NonNull Context context)
  {
    if (mPendingIntent != null)
      return mPendingIntent;

    final int FLAG_IMMUTABLE = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ? 0 : PendingIntent.FLAG_IMMUTABLE;
    final Intent contentIntent = new Intent(context, MwmActivity.class);
    mPendingIntent =
        PendingIntent.getActivity(context, 0, contentIntent, PendingIntent.FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE);
    return mPendingIntent;
  }

  private PendingIntent getExitPendingIntent(@NonNull Context context)
  {
    if (mExitPendingIntent != null)
      return mExitPendingIntent;

    final int FLAG_IMMUTABLE = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ? 0 : PendingIntent.FLAG_IMMUTABLE;
    final Intent exitIntent = new Intent(context, MwmActivity.class);
    exitIntent.setAction(STOP_TRACK_RECORDING);
    mExitPendingIntent =
        PendingIntent.getActivity(context, 1, exitIntent, PendingIntent.FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE);
    return mExitPendingIntent;
  }

  @NonNull
  public NotificationCompat.Builder getNotificationBuilder(@NonNull Context context)
  {
    if (mNotificationBuilder != null)
      return mNotificationBuilder;

    mNotificationBuilder =
        new NotificationCompat.Builder(context, TRACK_REC_CHANNEL_ID)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationManager.IMPORTANCE_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setShowWhen(true)
            .setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.ic_logo_small)
            .setContentTitle(context.getString(R.string.track_recording))
            .addAction(0, context.getString(R.string.navigation_stop_button), getExitPendingIntent(context))
            .setContentIntent(getPendingIntent(context))
            .setColor(ContextCompat.getColor(context, R.color.notification));

    return mNotificationBuilder;
  }

  public static void stopService(@NonNull Context context)
  {
    Logger.i(TAG);
    context.stopService(new Intent(context, TrackRecordingService.class));
  }

  @Override
  public void onDestroy()
  {
    mNotificationBuilder = null;
    mWarningBuilder = null;
    if (TrackRecorder.nativeIsTrackRecordingEnabled())
      TrackRecorder.nativeStopTrackRecording();
    MwmApplication.from(this).getLocationHelper().removeListener(this);
    // The notification is cancelled automatically by the system.
  }

  @Override
  public int onStartCommand(@NonNull Intent intent, int flags, int startId)
  {
    if (!MwmApplication.from(this).getOrganicMaps().arePlatformAndCoreInitialized())
    {
      Logger.w(TAG, "Application is not initialized");
      stopSelf();
      return START_NOT_STICKY; // The service will be stopped by stopSelf().
    }

    if (!LocationUtils.checkFineLocationPermission(this))
    {
      // In a hypothetical scenario, the user could revoke location permissions after the app's process crashed,
      // but before the service with START_STICKY was restarted by the system.
      Logger.w(TAG, "Permission ACCESS_FINE_LOCATION is not granted, skipping TrackRecordingService");
      stopSelf();
      return START_NOT_STICKY; // The service will be stopped by stopSelf().
    }

    if (!TrackRecorder.nativeIsTrackRecordingEnabled())
    {
      Logger.i(TAG, "Service can't be started because Track Recorder is turned off in settings");
      stopSelf();
      return START_NOT_STICKY;
    }

    Logger.i(TAG, "Starting Track Recording Foreground service");

    try
    {
      int type = 0;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
      ServiceCompat.startForeground(this, TrackRecordingService.TRACK_REC_NOTIFICATION_ID,
                                    getNotificationBuilder(this).build(), type);
    }
    catch (Exception e)
    {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e instanceof ForegroundServiceStartNotAllowedException)
      {
        // App not in a valid state to start foreground service (e.g started from bg)
        Logger.e(TAG, "Not in a valid state to start foreground service", e);
      }
      else
        Logger.e(TAG, "Failed to promote the service to foreground", e);
    }

    final LocationHelper locationHelper = MwmApplication.from(this).getLocationHelper();

    // Subscribe to location updates. This call is idempotent.
    locationHelper.addListener(this);

    // Restart the location with more frequent refresh interval for Track Recording.
    locationHelper.restartWithNewMode();

    return START_NOT_STICKY;
  }

  public NotificationCompat.Builder getWarningBuilder(Context context)
  {
    if (mWarningBuilder != null)
      return mWarningBuilder;

    mWarningBuilder =
        new NotificationCompat.Builder(context, TRACK_REC_CHANNEL_ID)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationManager.IMPORTANCE_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setShowWhen(true)
            .setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.warning_icon)
            .setContentTitle(context.getString(R.string.current_location_unknown_error_title))
            .setContentText(context.getString(R.string.dialog_routing_location_turn_wifi))
            .setStyle(new NotificationCompat.BigTextStyle().bigText(
                context.getString(R.string.dialog_routing_location_turn_wifi)))
            .addAction(0, context.getString(R.string.navigation_stop_button), getExitPendingIntent(context))
            .setContentIntent(getPendingIntent(context))
            .setColor(ContextCompat.getColor(context, R.color.notification_warning));

    return mWarningBuilder;
  }

  @Override
  public void onLocationUpdateTimeout()
  {
    Logger.i(TAG, "Location update timeout");
    mWarningNotification = true;
    // post notification permission is not there but we will not stop the runnable because if
    // in between user gives permission then warning will not be updated until next restart
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        && ActivityCompat.checkSelfPermission(this, POST_NOTIFICATIONS) != PERMISSION_GRANTED)
      return;

    NotificationManagerCompat.from(this).notify(TRACK_REC_NOTIFICATION_ID, getWarningBuilder(this).build());
  }

  @Override
  public void onLocationUpdated(@NonNull Location location)
  {
    Logger.i(TAG, "Location is being updated in Track Recording service");

    if (mWarningNotification)
    {
      mWarningNotification = false;

      // post notification permission is not there but we will not stop the runnable because if
      // in between user gives permission then warning will not be updated until next restart
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
          && ActivityCompat.checkSelfPermission(this, POST_NOTIFICATIONS) != PERMISSION_GRANTED)
        return;

      NotificationManagerCompat.from(this).notify(TRACK_REC_NOTIFICATION_ID, getNotificationBuilder(this).build());
    }

    sendLocationToSupabase(location);
  }

  private void sendLocationToSupabase(Location location) {
      SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
      String token = prefs.getString("supabase_token", null);
      if (token == null) return;

      String SUPABASE_URL = "https://fiuuefswkunqrkhayfer.supabase.co/";
      String SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImZpdXVlZnN3a3VucXJraGF5ZmVyIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjU3MTE4MzEsImV4cCI6MjA4MTI4NzgzMX0.CsDjN1QXm611iGlJDdQjUlcufhnkpJ6wQVOJjSJ-wPA";

      Retrofit retrofit = new Retrofit.Builder()
              .baseUrl(SUPABASE_URL)
              .addConverterFactory(GsonConverterFactory.create())
              .build();

      SupabaseApi api = retrofit.create(SupabaseApi.class);

      JsonObject body = new JsonObject();
      body.addProperty("user_id", prefs.getString("supabase_user_id", ""));
      body.addProperty("latitude", location.getLatitude());
      body.addProperty("longitude", location.getLongitude());
      body.addProperty("speed", location.getSpeed());
      body.addProperty("speed", location.getSpeed());
      body.addProperty("altitude", location.getAltitude());
      
      androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
      String userName = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).getString(getString(R.string.pref_user_name), "Rider");
      body.addProperty("user_name", userName);

      // Bearer token
      String authHeader = "Bearer " + token;

      api.logLocation(SUPABASE_KEY, authHeader, "return=minimal", body).enqueue(new Callback<Void>() {
          @Override
          public void onResponse(Call<Void> call, Response<Void> response) {
              if (!response.isSuccessful()) {
                  Logger.e(TAG, "Failed to log location: " + response.code());
              }
          }

          @Override
          public void onFailure(Call<Void> call, Throwable t) {
              Logger.e(TAG, "Network error logging location", t);
          }
      });
  }
}
