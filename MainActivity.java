package com.example.miniiptv;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String PREFS_NAME = "mini_iptv_prefs";
    private static final String KEY_PLAYLIST_URL = "playlist_url";
    private static final String KEY_LAST_INDEX = "last_index";
    private static final String CACHE_FILENAME = "playlist_cache.m3u";
    private static final int MAX_SAME_CHANNEL_RETRIES = 1;
    private static final int MAX_AUTO_SKIPS = 3;

    private VideoView videoView;
    private TextView tvOsd;
    private LinearLayout drawerPanel;
    private EditText etPlaylistUrl;
    private Button btnLoad;
    private Button btnGroup;
    private Button btnAspect;
    private ListView lvChannels;

    private final List<Channel> allChannels = new ArrayList<Channel>();
    private final List<Channel> channels = new ArrayList<Channel>();
    private final List<String> groups = new ArrayList<String>();
    private int currentGroupIndex = 0;
    private boolean stretchToFill = false;

    private ChannelAdapter adapter;
    private SharedPreferences prefs;

    private int currentIndex = -1;
    private int sameChannelRetries = 0;
    private int consecutiveAutoSkips = 0;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideOsdRunnable = new Runnable() {
        @Override
        public void run() {
            tvOsd.setVisibility(View.GONE);
        }
    };

    private final Runnable retryCurrentChannelRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentIndex >= 0 && currentIndex < channels.size()) {
                sameChannelRetries++;
                Channel ch = channels.get(currentIndex);
                showOsd("Retrying (" + sameChannelRetries + "/" + MAX_SAME_CHANNEL_RETRIES + "): " + ch.name);
                startPlaybackUri(ch.url);
            }
        }
    };

    private final Runnable skipToNextChannelRunnable = new Runnable() {
        @Override
        public void run() {
            if (channels.isEmpty()) {
                return;
            }
            consecutiveAutoSkips++;
            int nextIndex = (currentIndex + 1) % channels.size();
            playChannelInternal(nextIndex, false);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        videoView = (VideoView) findViewById(R.id.videoView);
        tvOsd = (TextView) findViewById(R.id.tvOsd);
        drawerPanel = (LinearLayout) findViewById(R.id.drawerPanel);
        etPlaylistUrl = (EditText) findViewById(R.id.etPlaylistUrl);
        btnLoad = (Button) findViewById(R.id.btnLoad);
        btnGroup = (Button) findViewById(R.id.btnGroup);
        btnAspect = (Button) findViewById(R.id.btnAspect);
        lvChannels = (ListView) findViewById(R.id.lvChannels);

        adapter = new ChannelAdapter();
        lvChannels.setAdapter(adapter);

        setupVideoListeners();
        setupUiListeners();

        String savedUrl = prefs.getString(KEY_PLAYLIST_URL, "");
        etPlaylistUrl.setText(savedUrl);

        loadCachedPlaylist();
        if (savedUrl.length() > 0) {
            loadFromInput(savedUrl, channels.isEmpty());
        } else {
            etPlaylistUrl.requestFocus();
        }
    }

    private void setupVideoListeners() {
        videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                sameChannelRetries = 0;
                consecutiveAutoSkips = 0;
                cancelPendingPlaybackActions();
                mp.start();
            }
        });

        videoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                handleStreamFailure("Error " + what + "/" + extra);
                return true;
            }
        });
    }

    private void setupUiListeners() {
        btnLoad.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String input = etPlaylistUrl.getText().toString().trim();
                if (input.length() > 0) {
                    prefs.edit().putString(KEY_PLAYLIST_URL, input).apply();
                    loadFromInput(input, true);
                }
            }
        });

        btnGroup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleGroupFilter();
            }
        });

        btnAspect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleAspectRatio();
            }
        });

        lvChannels.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                playChannel(position);
                setDrawerVisible(false);
            }
        });
    }

    private void loadFromInput(String input, boolean autoPlay) {
        if (input.startsWith("file://")) {
            parseLocalFileAsync(new File(Uri.parse(input).getPath()), autoPlay);
        } else if (input.startsWith("/")) {
            parseLocalFileAsync(new File(input), autoPlay);
        } else {
            fetchAndCachePlaylist(input, autoPlay);
        }
    }

    private void loadCachedPlaylist() {
        File cacheFile = new File(getFilesDir(), CACHE_FILENAME);
        if (!cacheFile.exists()) {
            return;
        }
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(cacheFile);
            List<Channel> parsed = M3uParser.parse(fis);
            applyChannelList(parsed, true);
        } catch (Exception e) {
            showOsd("Failed to read cached playlist");
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void parseLocalFileAsync(final File file, final boolean autoPlay) {
        showOsd("Reading local file...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                FileInputStream fis = null;
                try {
                    if (!file.exists() || !file.canRead()) {
                        throw new Exception("File not readable");
                    }
                    fis = new FileInputStream(file);
                    final List<Channel> parsed = M3uParser.parse(fis);
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            applyChannelList(parsed, autoPlay);
                            showOsd("Loaded " + parsed.size() + " channels");
                        }
                    });
                } catch (final Exception e) {
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            showOsd("Local file error: " + e.getMessage());
                        }
                    });
                } finally {
                    if (fis != null) {
                        try {
                            fis.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }).start();
    }

    private void fetchAndCachePlaylist(final String urlString, final boolean autoPlay) {
        showOsd("Loading playlist...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                InputStream in = null;
                FileOutputStream out = null;
                try {
                    URL url = new URL(urlString);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(12000);
                    conn.setRequestProperty("User-Agent", "MiniIPTV/1.0");
                    conn.connect();

                    int code = conn.getResponseCode();
                    if (code != HttpURLConnection.HTTP_OK) {
                        throw new Exception("HTTP " + code);
                    }

                    File tempFile = new File(getFilesDir(), CACHE_FILENAME + ".tmp");
                    in = conn.getInputStream();
                    out = new FileOutputStream(tempFile);
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                    out.flush();
                    out.close();
                    out = null;

                    File finalFile = new File(getFilesDir(), CACHE_FILENAME);
                    if (finalFile.exists()) {
                        finalFile.delete();
                    }
                    tempFile.renameTo(finalFile);

                    FileInputStream fis = new FileInputStream(finalFile);
                    final List<Channel> parsed = M3uParser.parse(fis);
                    fis.close();

                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            applyChannelList(parsed, autoPlay);
                            showOsd("Loaded " + parsed.size() + " channels");
                        }
                    });
                } catch (final Exception e) {
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            showOsd("Playlist error: " + e.getMessage());
                        }
                    });
                } finally {
                    try {
                        if (in != null) in.close();
                        if (out != null) out.close();
                        if (conn != null) conn.disconnect();
                    } catch (Exception ignored) {
                    }
                }
            }
        }).start();
    }

    private void rebuildGroups() {
        groups.clear();
        groups.add("All");
        for (int i = 0; i < allChannels.size(); i++) {
            String g = allChannels.get(i).group;
            if (!groups.contains(g)) {
                groups.add(g);
            }
        }
        currentGroupIndex = 0;
        btnGroup.setText("Group: All");
    }

    private void cycleGroupFilter() {
        if (groups.isEmpty()) {
            return;
        }
        currentGroupIndex = (currentGroupIndex + 1) % groups.size();
        String selectedGroup = groups.get(currentGroupIndex);
        btnGroup.setText("Group: " + selectedGroup);

        channels.clear();
        if ("All".equals(selectedGroup)) {
            channels.addAll(allChannels);
        } else {
            for (int i = 0; i < allChannels.size(); i++) {
                Channel ch = allChannels.get(i);
                if (selectedGroup.equals(ch.group)) {
                    channels.add(ch);
                }
            }
        }
        currentIndex = -1;
        adapter.notifyDataSetChanged();
        showOsd("Group: " + selectedGroup + " (" + channels.size() + ")");
    }

    private void toggleAspectRatio() {
        stretchToFill = !stretchToFill;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) videoView.getLayoutParams();
        params.width = FrameLayout.LayoutParams.MATCH_PARENT;
        params.height = FrameLayout.LayoutParams.MATCH_PARENT;
        params.gravity = stretchToFill ? Gravity.FILL : Gravity.CENTER;
        videoView.setLayoutParams(params);
        videoView.requestLayout();
        showOsd(stretchToFill ? "Aspect: Stretch / Fill" : "Aspect: Normal");
    }

    private void applyChannelList(List<Channel> newChannels, boolean autoPlay) {
        allChannels.clear();
        allChannels.addAll(newChannels);
        rebuildGroups();

        channels.clear();
        channels.addAll(allChannels);
        adapter.notifyDataSetChanged();

        if (!channels.isEmpty()) {
            int savedIndex = prefs.getInt(KEY_LAST_INDEX, 0);
            if (savedIndex < 0 || savedIndex >= channels.size()) {
                savedIndex = 0;
            }
            if (autoPlay) {
                playChannel(savedIndex);
                setDrawerVisible(false);
            } else {
                lvChannels.setSelection(savedIndex);
            }
        }
    }

    private void playChannel(int index) {
        sameChannelRetries = 0;
        consecutiveAutoSkips = 0;
        cancelPendingPlaybackActions();
        playChannelInternal(index, true);
    }

    private void playChannelInternal(int index, boolean isManual) {
        if (index < 0 || index >= channels.size()) {
            return;
        }
        currentIndex = index;
        prefs.edit().putInt(KEY_LAST_INDEX, index).apply();
        adapter.notifyDataSetChanged();

        Channel ch = channels.get(index);
        String prefix = isManual ? "" : "[Fallback] ";
        showOsd(prefix + (index + 1) + ". " + ch.name);
        startPlaybackUri(ch.url);
    }

    private void startPlaybackUri(String streamUrl) {
        try {
            videoView.stopPlayback();
            videoView.setVideoURI(Uri.parse(streamUrl));
        } catch (Exception e) {
            handleStreamFailure("Invalid stream URL");
        }
    }

    private void handleStreamFailure(String reason) {
        cancelPendingPlaybackActions();

        if (channels.isEmpty()) {
            showOsd("Playback failed: " + reason);
            return;
        }

        if (sameChannelRetries < MAX_SAME_CHANNEL_RETRIES) {
            showOsd("Stream error (" + reason + ") - retrying...");
            uiHandler.postDelayed(retryCurrentChannelRunnable, 1200);
            return;
        }

        if (consecutiveAutoSkips < MAX_AUTO_SKIPS) {
            showOsd("Unavailable (" + reason + ") - next channel...");
            uiHandler.postDelayed(skipToNextChannelRunnable, 1500);
        } else {
            showOsd("Stopped after " + MAX_AUTO_SKIPS + " failed channels (" + reason + ")");
        }
    }

    private void cancelPendingPlaybackActions() {
        uiHandler.removeCallbacks(retryCurrentChannelRunnable);
        uiHandler.removeCallbacks(skipToNextChannelRunnable);
    }

    private void switchChannelByDelta(int delta) {
        if (channels.isEmpty()) {
            return;
        }
        int next = (currentIndex + delta) % channels.size();
        if (next < 0) {
            next += channels.size();
        }
        playChannel(next);
    }

    private void setDrawerVisible(boolean visible) {
        if (visible) {
            drawerPanel.setVisibility(View.VISIBLE);
            if (!channels.isEmpty()) {
                lvChannels.requestFocus();
                if (currentIndex >= 0) {
                    lvChannels.setSelection(currentIndex);
                }
            } else {
                etPlaylistUrl.requestFocus();
            }
        } else {
            drawerPanel.setVisibility(View.GONE);
        }
    }

    private void showOsd(String text) {
        tvOsd.setText(text);
        tvOsd.setVisibility(View.VISIBLE);
        uiHandler.removeCallbacks(hideOsdRunnable);
        uiHandler.postDelayed(hideOsdRunnable, 3000);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            boolean drawerOpen = (drawerPanel.getVisibility() == View.VISIBLE);

            if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
                switchChannelByDelta(1);
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
                switchChannelByDelta(-1);
                return true;
            }

            if (!drawerOpen) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER
                        || keyCode == KeyEvent.KEYCODE_MENU) {
                    setDrawerVisible(true);
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    switchChannelByDelta(-1);
                    return true;
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    switchChannelByDelta(1);
                    return true;
                }
            } else {
                if (keyCode == KeyEvent.KEYCODE_BACK && !channels.isEmpty()) {
                    setDrawerVisible(false);
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        cancelPendingPlaybackActions();
        if (videoView != null && videoView.isPlaying()) {
            videoView.stopPlayback();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (currentIndex >= 0 && currentIndex < channels.size()) {
            playChannel(currentIndex);
        }
    }

    private class ChannelAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return channels.size();
        }

        @Override
        public Object getItem(int position) {
            return channels.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv;
            if (convertView == null) {
                tv = new TextView(MainActivity.this);
                tv.setPadding(12, 14, 12, 14);
                tv.setTextSize(16f);
                tv.setSingleLine(true);
            } else {
                tv = (TextView) convertView;
            }

            Channel ch = channels.get(position);
            tv.setText((position + 1) + ". " + ch.name);
            tv.setTextColor(position == currentIndex ? Color.parseColor("#4FC3F7") : Color.WHITE);
            return tv;
        }
    }
}
