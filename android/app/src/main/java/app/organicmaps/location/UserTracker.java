package app.organicmaps.location;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.JsonObject;

import java.util.List;

import app.organicmaps.R;
import app.organicmaps.api.SupabaseApi;
import app.organicmaps.sdk.bookmarks.data.Bookmark;
import app.organicmaps.sdk.bookmarks.data.BookmarkManager;
import app.organicmaps.sdk.bookmarks.data.Icon;
import app.organicmaps.sdk.bookmarks.data.PredefinedColors;
import app.organicmaps.sdk.util.log.Logger;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class UserTracker {
    private static final String TAG = "UserTracker";
    private static final long POLL_INTERVAL_MS = 10000; // 10 seconds
    private static final String CATEGORY_NAME = "Riders";

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final SupabaseApi mApi;
    private boolean mIsRunning = false;
    private long mCategoryId = -1;

    public UserTracker(Context context) {
        mContext = context;

        SharedPreferences prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        // Note: Ideally re-use the Retrofit instance, but for now creating a new one is fine
        String SUPABASE_URL = "https://fiuuefswkunqrkhayfer.supabase.co/";
        mApi = new Retrofit.Builder()
                .baseUrl(SUPABASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(SupabaseApi.class);
    }

    public void start() {
        if (mIsRunning) return;
        mIsRunning = true;
        mHandler.post(mPollRunnable);
    }

    public void stop() {
        mIsRunning = false;
        mHandler.removeCallbacks(mPollRunnable);
    }

    private final Runnable mPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mIsRunning) return;
            fetchAndShowUsers();
            mHandler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    private void fetchAndShowUsers() {
        SharedPreferences prefs = mContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        String token = prefs.getString("supabase_token", null);
        if (token == null) return;

        String SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImZpdXVlZnN3a3VucXJraGF5ZmVyIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NjU3MTE4MzEsImV4cCI6MjA4MTI4NzgzMX0.CsDjN1QXm611iGlJDdQjUlcufhnkpJ6wQVOJjSJ-wPA";
        String authHeader = "Bearer " + token;

        mApi.getLocations(SUPABASE_KEY, authHeader).enqueue(new Callback<List<JsonObject>>() {
            @Override
            public void onResponse(Call<List<JsonObject>> call, Response<List<JsonObject>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    updateMap(response.body());
                }
            }

            @Override
            public void onFailure(Call<List<JsonObject>> call, Throwable t) {
                Logger.e(TAG, "Failed to fetch users", t);
            }
        });
    }

    private void updateMap(List<JsonObject> users) {
        // Find or create category
        // Since we can't easily clear, we might delete and recreate.
        // But BookmarkManager operations must be on Main Thread? 
        // We are on Main Thread via Retrofit callback (if using default adapter, but let's be safe).
        
        // This logic presumes we are on UI thread. Retrofit defaults to Main thread on Android.
        
        BookmarkManager bm = BookmarkManager.INSTANCE;
        
        // Brute-force: Find category by name, delete it.
        // We can't iterate via name easily without iterating all.
        // Let's rely on stored ID, or simpler: just create it and it might duplicate name?
        // Framework ensures unique names normally.
        
        // Better approach: Iterate all categories, find "Riders", delete.
        // Getting all categories might be slow if many. 
        // Let's try to keep track of ID.
        
        if (mCategoryId != -1) {
             bm.deleteCategory(mCategoryId);
        }
        
        // Wait, if app restarted, mCategoryId is -1, but category might exist on disk.
        // We should find it.
        // Note: BookmarkManager doesn't expose "getCategoryByName".
        // functionality needs to be added or we just create "Riders" and if it becomes "Riders 1", so be it?
        // No, that's bad.
        
        // Let's try to just create NEW one every time for now (Delete old if we tracked it). 
        // If we didn't track it, we might leave artifacts. 
        // Improvement: Iterate categories to find "Riders".
        
        long foundId = -1;
        int count = bm.getCategoriesCount();
        java.util.List<app.organicmaps.sdk.bookmarks.data.BookmarkCategory> categories = bm.getCategories();
        for (app.organicmaps.sdk.bookmarks.data.BookmarkCategory cat : categories) {
            if (CATEGORY_NAME.equals(cat.getName())) {
                foundId = cat.getId();
                break;
            }
        }
        
        if (foundId != -1) {
            bm.deleteCategory(foundId);
        }

        mCategoryId = bm.createCategory(CATEGORY_NAME);
        
        // Now add users
        for (JsonObject u : users) {
             try {
                 double lat = u.get("latitude").getAsDouble();
                 double lon = u.get("longitude").getAsDouble();
                 String name = u.has("user_name") && !u.get("user_name").isJsonNull() ? u.get("user_name").getAsString() : "Rider";
                 
                 Bookmark b = bm.addNewBookmark(lat, lon);
                 if (b != null) {
                     bm.changeBookmarkCategory(b.getCategoryId(), mCategoryId, b.getBookmarkId());
                     bm.setBookmarkParams(b.getBookmarkId(), name, PredefinedColors.BLUE, "Speed: " + u.get("speed") + "\nAlt: " + u.get("altitude"));
                 }
             } catch (Exception e) {
                 Logger.e(TAG, "Error parsing user", e);
             }
        }
        
        // Make sure it's visible?
        bm.setVisibility(mCategoryId, true);
    }
}
