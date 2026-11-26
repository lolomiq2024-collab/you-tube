package com.example.ytplaylists;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    // رابط ملف القوائم على GitHub: عدّل الملف هناك فتتحدث القوائم تلقائياً دون إعادة بناء التطبيق
    private static final String CONFIG_URL =
            "https://raw.githubusercontent.com/lolomiq2024-collab/yt-playlists/main/playlists.json";

    // عناصر احتياطية تُستخدم فقط قبل أول تحميل ناجح للملف: {الاسم, المعرّف, النوع}
    // النوع: p = قائمة تشغيل ، v = فيديو مفرد
    private static final String[][] DEFAULTS = {
            {"مايا النحلة", "PL6qk-0ihmmB4O53zIbVntvbJIy_qgtbJk", "p"}
    };

    // هوية التضمين: معرّف حزمة التطبيق (يطلبها يوتيوب لتجنّب الخطأ 152/153)
    private static final String ORIGIN = "https://com.example.ytplaylists";

    private static final String PREFS = "yt_playlists";
    private static final String KEY_USER = "user_lists";
    private static final String KEY_REMOTE = "remote_json";
    private static final String KEY_LAST_ID = "last_id";

    private static final long REFRESH_INTERVAL_MS = 10 * 60 * 1000L;

    private static final int BG = 0xFF121212;
    private static final int ACCENT = 0xFFF5B301;

    private static final Pattern LIST_PARAM = Pattern.compile("[?&]list=([A-Za-z0-9_-]+)");
    private static final Pattern VIDEO_URL =
            Pattern.compile("(?:[?&]v=|youtu\\.be/|/shorts/|/live/|/embed/)([A-Za-z0-9_-]{11})");
    private static final Pattern LIST_ID = Pattern.compile("^(PL|UU|OL|FL|LL)[A-Za-z0-9_-]{10,}$");
    private static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
    private static final Pattern VALID_ID = Pattern.compile("^[A-Za-z0-9_-]{10,}$");

    private final ArrayList<String[]> remote = new ArrayList<>();
    private final ArrayList<String[]> user = new ArrayList<>();
    private final ArrayList<String[]> lists = new ArrayList<>();
    private int current = -1;
    private String currentId = null;
    private boolean playing = false;
    private long lastFetch = 0;

    private FrameLayout rootFrame;
    private LinearLayout content;
    private FrameLayout playerHolder;
    private WebView web;
    private BaseAdapter adapter;

    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        loadLists();
        setupAdapter();
        setupWebView();

        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(BG);
        setContentView(rootFrame);
        buildLayout();
        applySystemUi();

        fetchRemote();
    }

    // ---------- بيانات القوائم ----------

    private void loadLists() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);

        remote.clear();
        String cached = sp.getString(KEY_REMOTE, null);
        if (cached == null || !parseRemote(cached, remote)) {
            remote.clear();
            for (String[] d : DEFAULTS) remote.add(d);
        }

        user.clear();
        String raw = sp.getString(KEY_USER, null);
        if (raw != null) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    user.add(new String[]{o.getString("name"), o.getString("id"), o.optString("type", "p")});
                }
            } catch (Exception ignored) {
            }
        }
        rebuild();
    }

    private void saveUser() {
        try {
            JSONArray arr = new JSONArray();
            for (String[] l : user) {
                JSONObject o = new JSONObject();
                o.put("name", l[0]);
                o.put("id", l[1]);
                o.put("type", l[2]);
                arr.put(o);
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_USER, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void rebuild() {
        lists.clear();
        lists.addAll(remote);
        lists.addAll(user);
        current = indexOfId(currentId);
    }

    private int indexOfId(String id) {
        if (id == null) return -1;
        for (int i = 0; i < lists.size(); i++) {
            if (lists.get(i)[1].equals(id)) return i;
        }
        return -1;
    }

    // يستخرج {المعرّف, النوع} من رابط يوتيوب (قائمة أو فيديو) أو من معرّف مجرّد
    private static String[] extractTarget(String input) {
        if (input == null) return null;
        input = input.trim();
        if (input.isEmpty()) return null;
        Matcher m = LIST_PARAM.matcher(input);
        if (m.find()) return new String[]{m.group(1), "p"};
        m = VIDEO_URL.matcher(input);
        if (m.find()) return new String[]{m.group(1), "v"};
        if (LIST_ID.matcher(input).matches()) return new String[]{input, "p"};
        if (VIDEO_ID.matcher(input).matches()) return new String[]{input, "v"};
        return null;
    }

    private static boolean parseRemote(String raw, ArrayList<String[]> out) {
        try {
            String text = raw.trim();
            if (text.startsWith("﻿")) text = text.substring(1);
            JSONObject root = new JSONObject(text);
            JSONArray arr = root.getJSONArray("playlists");
            ArrayList<String[]> tmp = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String name = o.getString("name").trim();
                String[] t = null;
                if (o.has("url")) {
                    t = extractTarget(o.getString("url"));
                } else if (o.has("video")) {
                    String v = o.getString("video").trim();
                    if (VIDEO_ID.matcher(v).matches()) t = new String[]{v, "v"};
                } else if (o.has("id")) {
                    String id = o.getString("id").trim();
                    if (VALID_ID.matcher(id).matches()) t = new String[]{id, "p"};
                }
                if (!name.isEmpty() && t != null) {
                    tmp.add(new String[]{name, t[0], t[1]});
                }
            }
            if (tmp.isEmpty()) return false;
            out.clear();
            out.addAll(tmp);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // تحميل ملف القوائم من الإنترنت وتحديث القائمة دون مقاطعة التشغيل الحالي
    private void fetchRemote() {
        lastFetch = System.currentTimeMillis();
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(CONFIG_URL + "?t=" + System.currentTimeMillis()).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setUseCaches(false);
                if (c.getResponseCode() != 200) return;
                InputStream in = c.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                String raw = bos.toString("UTF-8");

                ArrayList<String[]> parsed = new ArrayList<>();
                if (!parseRemote(raw, parsed)) return;

                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_REMOTE, raw).apply();

                runOnUiThread(() -> {
                    remote.clear();
                    remote.addAll(parsed);
                    rebuild();
                    adapter.notifyDataSetChanged();
                });
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    // ---------- المشغّل ----------

    private void setupWebView() {
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true; // لا نسمح بالخروج من المشغّل المضمّن
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customCallback = callback;
                rootFrame.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                content.setVisibility(View.GONE);
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                rootFrame.removeView(customView);
                customView = null;
                content.setVisibility(View.VISIBLE);
                applySystemUi();
                if (customCallback != null) {
                    customCallback.onCustomViewHidden();
                    customCallback = null;
                }
            }
        });
    }

    // type: p = قائمة تشغيل ، v = فيديو مفرد (يعاد تشغيله تلقائياً عند انتهائه)
    private static String playerHtml(String id, String type) {
        boolean video = "v".equals(type);
        String src = video ? "videoId:'" + id + "'," : "";
        String vars = video
                ? "loop:1,playlist:'" + id + "',"
                : "listType:'playlist',list:'" + id + "',loop:1,";
        return "<!DOCTYPE html><html><head>"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden;position:relative}"
                + "#p{width:100%;height:100%}</style></head><body><div id=\"p\"></div>"
                + "<script src=\"https://www.youtube.com/iframe_api\"></script>"
                + "<script>"
                + "var player,errs=0;"
                // تكبير إطار المشغّل ليملأ الشاشة كاملة (تغطية) بدل بقاء الصورة في الوسط
                + "function fit(){var f=document.querySelector('iframe');if(!f)return;"
                + "var W=window.innerWidth,H=window.innerHeight,w=Math.max(W,H*16/9),h=w*9/16;"
                + "f.style.position='absolute';f.style.width=w+'px';f.style.height=h+'px';"
                + "f.style.left=((W-w)/2)+'px';f.style.top=((H-h)/2)+'px';}"
                + "window.addEventListener('resize',fit);"
                + "function onYouTubeIframeAPIReady(){"
                + "player=new YT.Player('p',{width:'100%',height:'100%'," + src
                + "playerVars:{" + vars + "autoplay:1,playsinline:1,rel:0,enablejsapi:1,"
                + "origin:'" + ORIGIN + "',widget_referrer:'" + ORIGIN + "'},"
                + "events:{"
                + "onReady:function(e){fit();e.target.playVideo();setTimeout(fit,500);},"
                + "onStateChange:function(e){fit();if(e.data===1){errs=0;}"
                + (video ? "if(e.data===0){try{player.seekTo(0);player.playVideo();}catch(x){}}" : "")
                + "},"
                + "onError:function(e){errs++;if(errs<60){try{player.nextVideo();}catch(x){}}}"
                + "}});}"
                + "</script></body></html>";
    }

    private void play(int index) {
        if (index < 0 || index >= lists.size()) return;
        String[] item = lists.get(index);
        current = index;
        currentId = item[1];
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAST_ID, currentId).apply();
        adapter.notifyDataSetChanged();
        playing = true;
        // التشغيل بوضع أفقي ليملأ الفيديو الشاشة
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        buildLayout();
        applySystemUi();
        web.loadDataWithBaseURL(ORIGIN, playerHtml(item[1], item[2]), "text/html", "utf-8", null);
    }

    // العودة إلى الصفحة الرئيسية وإيقاف التشغيل
    private void goHome() {
        playing = false;
        currentId = null;
        current = -1;
        web.loadUrl("about:blank");
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        buildLayout();
        applySystemUi();
        adapter.notifyDataSetChanged();
    }

    // ---------- الواجهة ----------

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void setupAdapter() {
        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return lists.size();
            }

            @Override
            public Object getItem(int i) {
                return lists.get(i);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int i, View convert, ViewGroup parent) {
                TextView t = (convert instanceof TextView) ? (TextView) convert : new TextView(MainActivity.this);
                t.setPadding(dp(16), dp(14), dp(16), dp(14));
                t.setTextSize(18);
                t.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                boolean sel = i == current;
                t.setText((sel ? "▶  " : "") + lists.get(i)[0]);
                t.setTextColor(sel ? ACCENT : Color.WHITE);
                t.setBackgroundColor(sel ? 0xFF262626 : Color.TRANSPARENT);
                return t;
            }
        };
    }

    private void buildLayout() {
        if (web.getParent() != null) ((ViewGroup) web.getParent()).removeView(web);
        if (content != null) rootFrame.removeView(content);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(BG);
        content.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        playerHolder = new FrameLayout(this);
        playerHolder.setBackgroundColor(Color.BLACK);
        playerHolder.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView toggle = new TextView(this);
        toggle.setText("☰");
        toggle.setTextColor(Color.WHITE);
        toggle.setTextSize(22);
        toggle.setGravity(Gravity.CENTER);
        toggle.setBackgroundColor(0x66000000);
        toggle.setOnClickListener(v -> goHome());
        playerHolder.addView(toggle, new FrameLayout.LayoutParams(
                dp(44), dp(44), Gravity.LEFT | Gravity.CENTER_VERTICAL));

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);

        Button add = new Button(this);
        add.setText("+ إضافة رابط");
        add.setTextColor(Color.BLACK);
        add.setBackgroundColor(ACCENT);
        add.setOnClickListener(v -> showAddDialog());
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addLp.setMargins(dp(12), dp(12), dp(12), dp(8));
        side.addView(add, addLp);

        ListView listView = new ListView(this);
        listView.setAdapter(adapter);
        listView.setDivider(new ColorDrawable(0xFF2A2A2A));
        listView.setDividerHeight(1);
        listView.setOnItemClickListener((p, v, pos, id) -> play(pos));
        listView.setOnItemLongClickListener((p, v, pos, id) -> {
            if (pos >= remote.size()) confirmDelete(pos);
            return true;
        });
        side.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        if (playing) {
            content.addView(playerHolder, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            content.addView(side, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        rootFrame.addView(content, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (customView != null) content.setVisibility(View.GONE);
    }

    private void applySystemUi() {
        if (customView != null) return;
        getWindow().getDecorView().setSystemUiVisibility(playing
                ? (View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
                : View.SYSTEM_UI_FLAG_VISIBLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applySystemUi();
    }

    private void showAddDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), 0);
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        TextView nameLabel = new TextView(this);
        nameLabel.setText("الاسم");
        EditText nameInput = new EditText(this);
        nameInput.setSingleLine(true);

        TextView linkLabel = new TextView(this);
        linkLabel.setText("رابط القائمة أو الفيديو");
        EditText linkInput = new EditText(this);
        linkInput.setSingleLine(true);

        box.addView(nameLabel);
        box.addView(nameInput);
        box.addView(linkLabel);
        box.addView(linkInput);

        new AlertDialog.Builder(this)
                .setTitle("إضافة رابط")
                .setView(box)
                .setPositiveButton("إضافة", (d, w) -> {
                    String[] t = extractTarget(linkInput.getText().toString());
                    if (t == null) {
                        Toast.makeText(this, "الرابط غير صحيح", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty()) {
                        name = ("v".equals(t[1]) ? "فيديو " : "قائمة ") + (lists.size() + 1);
                    }
                    user.add(new String[]{name, t[0], t[1]});
                    saveUser();
                    rebuild();
                    adapter.notifyDataSetChanged();
                    play(lists.size() - 1);
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void confirmDelete(int pos) {
        new AlertDialog.Builder(this)
                .setTitle(lists.get(pos)[0])
                .setMessage("حذف هذا العنصر؟")
                .setPositiveButton("حذف", (d, w) -> {
                    int userIndex = pos - remote.size();
                    if (userIndex < 0 || userIndex >= user.size()) return;
                    boolean wasCurrent = pos == current;
                    user.remove(userIndex);
                    saveUser();
                    rebuild();
                    if (wasCurrent) {
                        currentId = null;
                        current = -1;
                    }
                    adapter.notifyDataSetChanged();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    // ---------- دورة الحياة ----------

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        buildLayout();
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            web.getWebChromeClient().onHideCustomView();
        } else if (playing) {
            goHome();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        if (System.currentTimeMillis() - lastFetch > REFRESH_INTERVAL_MS) fetchRemote();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            if (web.getParent() != null) ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }
}
