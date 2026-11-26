package com.example.ytplaylists;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
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

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String PREFS = "yt_playlists";
    private static final String KEY_LISTS = "lists";
    private static final String KEY_LAST = "last";

    // هوية التضمين: معرّف حزمة التطبيق (يطلبها يوتيوب لتجنّب الخطأ 152/153)
    private static final String ORIGIN = "https://com.example.ytplaylists";

    private static final int BG = 0xFF121212;
    private static final int ACCENT = 0xFFF5B301;

    private static final Pattern LIST_PARAM = Pattern.compile("[?&]list=([A-Za-z0-9_-]+)");
    private static final Pattern LIST_ID = Pattern.compile("^(PL|UU|OL|FL|LL)[A-Za-z0-9_-]{10,}$");

    private final ArrayList<String[]> lists = new ArrayList<>();
    private int current = -1;

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

        if (!lists.isEmpty()) {
            int last = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_LAST, 0);
            if (last < 0 || last >= lists.size()) last = 0;
            play(last);
        }
    }

    // ---------- بيانات القوائم ----------

    private void loadLists() {
        lists.clear();
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String raw = sp.getString(KEY_LISTS, null);
        if (raw == null) {
            lists.add(new String[]{"مايا النحلة", "PL6qk-0ihmmB4O53zIbVntvbJIy_qgtbJk"});
            saveLists();
            return;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                lists.add(new String[]{o.getString("name"), o.getString("id")});
            }
        } catch (Exception ignored) {
        }
    }

    private void saveLists() {
        try {
            JSONArray arr = new JSONArray();
            for (String[] l : lists) {
                JSONObject o = new JSONObject();
                o.put("name", l[0]);
                o.put("id", l[1]);
                arr.put(o);
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LISTS, arr.toString()).apply();
        } catch (Exception ignored) {
        }
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
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
                if (customCallback != null) {
                    customCallback.onCustomViewHidden();
                    customCallback = null;
                }
            }
        });
    }

    private static String playerHtml(String listId) {
        return "<!DOCTYPE html><html><head>"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}"
                + "#p{width:100%;height:100%}</style></head><body><div id=\"p\"></div>"
                + "<script src=\"https://www.youtube.com/iframe_api\"></script>"
                + "<script>"
                + "var player,errs=0;"
                + "function onYouTubeIframeAPIReady(){"
                + "player=new YT.Player('p',{width:'100%',height:'100%',"
                + "playerVars:{listType:'playlist',list:'" + listId + "',autoplay:1,loop:1,"
                + "playsinline:1,rel:0,enablejsapi:1,origin:'" + ORIGIN + "',widget_referrer:'" + ORIGIN + "'},"
                + "events:{"
                + "onReady:function(e){e.target.playVideo();},"
                + "onStateChange:function(e){if(e.data===1){errs=0;}},"
                + "onError:function(e){errs++;if(errs<60){try{player.nextVideo();}catch(x){}}}"
                + "}});}"
                + "</script></body></html>";
    }

    private void play(int index) {
        if (index < 0 || index >= lists.size()) return;
        current = index;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_LAST, index).apply();
        adapter.notifyDataSetChanged();
        web.loadDataWithBaseURL(ORIGIN, playerHtml(lists.get(index)[1]),
                "text/html", "utf-8", null);
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
        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;

        if (web.getParent() != null) ((ViewGroup) web.getParent()).removeView(web);
        if (content != null) rootFrame.removeView(content);

        content = new LinearLayout(this);
        content.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        content.setBackgroundColor(BG);
        content.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        playerHolder = new FrameLayout(this);
        playerHolder.setBackgroundColor(Color.BLACK);
        playerHolder.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);

        Button add = new Button(this);
        add.setText("+ إضافة قائمة تشغيل");
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
            confirmDelete(pos);
            return true;
        });
        side.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        if (land) {
            content.addView(playerHolder, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3f));
            content.addView(side, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f));
        } else {
            int h = getResources().getDisplayMetrics().widthPixels * 9 / 16;
            content.addView(playerHolder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
            content.addView(side, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }

        rootFrame.addView(content, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (customView != null) content.setVisibility(View.GONE);
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
        linkLabel.setText("رابط القائمة");
        EditText linkInput = new EditText(this);
        linkInput.setSingleLine(true);

        box.addView(nameLabel);
        box.addView(nameInput);
        box.addView(linkLabel);
        box.addView(linkInput);

        new AlertDialog.Builder(this)
                .setTitle("إضافة قائمة تشغيل")
                .setView(box)
                .setPositiveButton("إضافة", (d, w) -> {
                    String id = extractListId(linkInput.getText().toString().trim());
                    if (id == null) {
                        Toast.makeText(this, "الرابط غير صحيح", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty()) name = "قائمة " + (lists.size() + 1);
                    lists.add(new String[]{name, id});
                    saveLists();
                    adapter.notifyDataSetChanged();
                    play(lists.size() - 1);
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private static String extractListId(String input) {
        if (input.isEmpty()) return null;
        Matcher m = LIST_PARAM.matcher(input);
        if (m.find()) return m.group(1);
        if (LIST_ID.matcher(input).matches()) return input;
        return null;
    }

    private void confirmDelete(int pos) {
        new AlertDialog.Builder(this)
                .setTitle(lists.get(pos)[0])
                .setMessage("حذف هذه القائمة؟")
                .setPositiveButton("حذف", (d, w) -> {
                    boolean wasCurrent = pos == current;
                    lists.remove(pos);
                    saveLists();
                    if (wasCurrent) {
                        current = -1;
                        if (!lists.isEmpty()) {
                            play(0);
                        } else {
                            web.loadUrl("about:blank");
                        }
                    } else if (pos < current) {
                        current--;
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
