package app.grapheneos.pdfviewer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;

import com.google.android.material.snackbar.Snackbar;

import app.grapheneos.pdfviewer.databinding.PdfviewerBinding;
import app.grapheneos.pdfviewer.fragment.DocumentPropertiesFragment;
import app.grapheneos.pdfviewer.fragment.JumpToPageFragment;
import app.grapheneos.pdfviewer.loader.DocumentPropertiesLoader;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;

public class PdfViewer extends AppCompatActivity implements LoaderManager.LoaderCallbacks<List<CharSequence>> {
    public static final String TAG = "PdfViewer";

    private static final String STATE_URI = "uri";
    private static final String STATE_PAGE = "page";
    private static final String STATE_ZOOM_RATIO = "zoomRatio";
    private static final String STATE_DOCUMENT_ORIENTATION_DEGREES = "documentOrientationDegrees";
    private static final String KEY_PROPERTIES = "properties";
    private static final int MIN_WEBVIEW_RELEASE = 89;

    private static final String CONTENT_SECURITY_POLICY =
        "default-src 'none'; " +
        "form-action 'none'; " +
        "connect-src https://localhost/placeholder.pdf; " +
        "img-src blob: 'self'; " +
        "script-src 'self'; " +
        "style-src 'self'; " +
        "frame-ancestors 'none'; " +
        "base-uri 'none'";

    private static final String PERMISSIONS_POLICY =
        "accelerometer=(), " +
        "ambient-light-sensor=(), " +
        "autoplay=(), " +
        "battery=(), " +
        "camera=(), " +
        "clipboard-read=(), " +
        "clipboard-write=(), " +
        "display-capture=(), " +
        "document-domain=(), " +
        "encrypted-media=(), " +
        "fullscreen=(), " +
        "geolocation=(), " +
        "gyroscope=(), " +
        "hid=(), " +
        "idle-detection=(), " +
        "interest-cohort=(), " +
        "magnetometer=(), " +
        "microphone=(), " +
        "midi=(), " +
        "payment=(), " +
        "picture-in-picture=(), " +
        "publickey-credentials-get=(), " +
        "screen-wake-lock=(), " +
        "serial=(), " +
        "sync-xhr=(), " +
        "usb=(), " +
        "xr-spatial-tracking=()";

    private static final float MIN_ZOOM_RATIO = 0.5f;
    private static final float MAX_ZOOM_RATIO = 1.5f;
    private static final int ALPHA_LOW = 130;
    private static final int ALPHA_HIGH = 255;
    private static final int ACTION_OPEN_DOCUMENT_REQUEST_CODE = 1;
    private static final int STATE_LOADED = 1;
    private static final int STATE_END = 2;
    private static final int PADDING = 10;

    private Uri mUri;
    public int mPage;
    public int mNumPages;
    private float mZoomRatio = 1f;
    private int mDocumentOrientationDegrees;
    private int mDocumentState;
    private List<CharSequence> mDocumentProperties;
    private InputStream mInputStream;

    private PdfviewerBinding binding;
    private TextView mTextView;
    private Toast mToast;
    private Snackbar snackbar;

    private class Channel {
        @JavascriptInterface
        public int getPage() {
            return mPage;
        }

        @JavascriptInterface
        public float getZoomRatio() {
            return mZoomRatio;
        }

        @JavascriptInterface
        public int getDocumentOrientationDegrees() {
            return mDocumentOrientationDegrees;
        }

        @JavascriptInterface
        public void setNumPages(int numPages) {
            mNumPages = numPages;
            runOnUiThread(PdfViewer.this::invalidateOptionsMenu);
        }

        @JavascriptInterface
        public void setDocumentProperties(final String properties) {
            if (mDocumentProperties != null) {
                throw new SecurityException("mDocumentProperties not null");
            }

            final Bundle args = new Bundle();
            args.putString(KEY_PROPERTIES, properties);
            runOnUiThread(() -> LoaderManager.getInstance(PdfViewer.this).restartLoader(DocumentPropertiesLoader.ID, args, PdfViewer.this));
        }
    }

    @Override
    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = PdfviewerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        // Margins for the toolbar are needed, so that content of the toolbar
        // is not covered by a system button navigation bar when in landscape.
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            mlp.leftMargin = insets.left;
            mlp.rightMargin = insets.right;
            v.setLayoutParams(mlp);
            return windowInsets;
        });

        binding.webview.setBackgroundColor(Color.TRANSPARENT);

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        final WebSettings settings = binding.webview.getSettings();
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccess(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setJavaScriptEnabled(true);

        CookieManager.getInstance().setAcceptCookie(false);

        binding.webview.addJavascriptInterface(new Channel(), "channel");

        binding.webview.setWebViewClient(new WebViewClient() {
            private WebResourceResponse fromAsset(final String mime, final String path) {
                try {
                    InputStream inputStream = getAssets().open(path.substring(1));
                    return new WebResourceResponse(mime, null, inputStream);
                } catch (IOException e) {
                    return null;
                }
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (!"GET".equals(request.getMethod())) {
                    return null;
                }

                final Uri url = request.getUrl();
                if (!"localhost".equals(url.getHost())) {
                    return null;
                }

                final String path = url.getPath();
                Log.d(TAG, "path " + path);

                if ("/placeholder.pdf".equals(path)) {
                    return new WebResourceResponse("application/pdf", null, mInputStream);
                }

                if ("/viewer.html".equals(path)) {
                    final WebResourceResponse response = fromAsset("text/html", path);
                    HashMap<String, String> headers = new HashMap<>();
                    headers.put("Content-Security-Policy", CONTENT_SECURITY_POLICY);
                    headers.put("Permissions-Policy", PERMISSIONS_POLICY);
                    headers.put("X-Content-Type-Options", "nosniff");
                    response.setResponseHeaders(headers);
                    return response;
                }

                if ("/viewer.css".equals(path)) {
                    return fromAsset("text/css", path);
                }

                if ("/viewer.js".equals(path) || "/pdf.js".equals(path) || "/pdf.worker.js".equals(path)) {
                    return fromAsset("application/javascript", path);
                }

                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                mDocumentState = STATE_LOADED;
                invalidateOptionsMenu();
            }
        });

        GestureHelper.attach(PdfViewer.this, binding.webview,
                new GestureHelper.GestureListener() {
                    @Override
                    public boolean onTapUp() {
                        if (mUri != null) {
                            binding.webview.evaluateJavascript("isTextSelected()", selection -> {
                                if (!Boolean.parseBoolean(selection)) {
                                    if ((getWindow().getDecorView().getSystemUiVisibility() &
                                            View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                                        hideSystemUi();
                                    } else {
                                        showSystemUi();
                                    }
                                }
                            });
                            return true;
                        }
                        return false;
                    }

                    @Override
                    public void onZoomIn(float value) {
                        zoomIn(value, false);
                    }

                    @Override
                    public void onZoomOut(float value) {
                        zoomOut(value, false);
                    }

                    @Override
                    public void onZoomEnd() {
                        zoomEnd();
                    }
                });

        mTextView = new TextView(this);
        mTextView.setBackgroundColor(Color.DKGRAY);
        mTextView.setTextColor(ColorStateList.valueOf(Color.WHITE));
        mTextView.setTextSize(18);
        mTextView.setPadding(PADDING, 0, PADDING, 0);

        // If loaders are not being initialized in onCreate(), the result will not be delivered
        // after orientation change (See FragmentHostCallback), thus initialize the
        // loader manager impl so that the result will be delivered.
        LoaderManager.getInstance(this);

        snackbar = Snackbar.make(binding.webview, "", Snackbar.LENGTH_LONG);

        final Intent intent = getIntent();
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            if (!"application/pdf".equals(intent.getType())) {
                snackbar.setText(R.string.invalid_mime_type).show();
                return;
            }
            mUri = intent.getData();
            mPage = 1;
        }

        if (savedInstanceState != null) {
            mUri = savedInstanceState.getParcelable(STATE_URI);
            mPage = savedInstanceState.getInt(STATE_PAGE);
            mZoomRatio = savedInstanceState.getFloat(STATE_ZOOM_RATIO);
            mDocumentOrientationDegrees = savedInstanceState.getInt(STATE_DOCUMENT_ORIENTATION_DEGREES);
        }

        if (mUri != null) {
            if ("file".equals(mUri.getScheme())) {
                snackbar.setText(R.string.legacy_file_uri).show();
                return;
            }

            loadPdf();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // The user could have left the activity to update the WebView
        invalidateOptionsMenu();
        if (getWebViewRelease() >= MIN_WEBVIEW_RELEASE) {
            binding.webviewOutOfDateLayout.setVisibility(View.GONE);
            binding.webview.setVisibility(View.VISIBLE);
        } else {
            binding.webview.setVisibility(View.GONE);
            binding.webviewOutOfDateMessage.setText(getString(R.string.webview_out_of_date_message, getWebViewRelease(), MIN_WEBVIEW_RELEASE));
            binding.webviewOutOfDateLayout.setVisibility(View.VISIBLE);
        }
    }

    private int getWebViewRelease() {
        PackageInfo webViewPackage = WebView.getCurrentWebViewPackage();
        String webViewVersionName = webViewPackage.versionName;
        return Integer.parseInt(webViewVersionName.substring(0, webViewVersionName.indexOf(".")));
    }

    @NonNull
    @Override
    public Loader<List<CharSequence>> onCreateLoader(int id, Bundle args) {
        return new DocumentPropertiesLoader(this, args.getString(KEY_PROPERTIES), mNumPages, mUri);
    }

    @Override
    public void onLoadFinished(@NonNull Loader<List<CharSequence>> loader, List<CharSequence> data) {
        mDocumentProperties = data;
        LoaderManager.getInstance(this).destroyLoader(DocumentPropertiesLoader.ID);
    }

    @Override
    public void onLoaderReset(@NonNull Loader<List<CharSequence>> loader) {
        mDocumentProperties = null;
    }

    private void loadPdf() {
        try {
            if (mInputStream != null) {
                mInputStream.close();
            }
            mInputStream = getContentResolver().openInputStream(mUri);
        } catch (IOException e) {
            snackbar.setText(R.string.io_error).show();
            return;
        }

        showSystemUi();
        binding.webview.loadUrl("https://localhost/viewer.html");
    }

    private void renderPage(final int zoom) {
        binding.webview.evaluateJavascript("onRenderPage(" + zoom + ")", null);
    }

    private void documentOrientationChanged(final int orientationDegreesOffset) {
        mDocumentOrientationDegrees = (mDocumentOrientationDegrees + orientationDegreesOffset) % 360;
        if (mDocumentOrientationDegrees < 0) {
            mDocumentOrientationDegrees += 360;
        }
        renderPage(0);
    }

    private void openDocument() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        startActivityForResult(intent, ACTION_OPEN_DOCUMENT_REQUEST_CODE);
    }

    private void zoomIn(float value, boolean end) {
        if (mZoomRatio < MAX_ZOOM_RATIO) {
            mZoomRatio = Math.min(mZoomRatio + value, MAX_ZOOM_RATIO);
            renderPage(end ? 1 : 2);
            invalidateOptionsMenu();
        }
    }

    private void zoomOut(float value, boolean end) {
        if (mZoomRatio > MIN_ZOOM_RATIO) {
            mZoomRatio = Math.max(mZoomRatio - value, MIN_ZOOM_RATIO);
            renderPage(end ? 1 : 2);
            invalidateOptionsMenu();
        }
    }

    private void zoomEnd() {
        renderPage(1);
    }

    private static void enableDisableMenuItem(MenuItem item, boolean enable) {
        if (enable) {
            item.setEnabled(true);
            item.getIcon().setAlpha(ALPHA_HIGH);
        } else {
            item.setEnabled(false);
            item.getIcon().setAlpha(ALPHA_LOW);
        }
    }

    public void onJumpToPageInDocument(final int selected_page) {
        if (selected_page >= 1 && selected_page <= mNumPages && mPage != selected_page) {
            mPage = selected_page;
            renderPage(0);
            showPageNumber();
            invalidateOptionsMenu();
        }
    }

    private void showSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        getSupportActionBar().show();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_IMMERSIVE);
        getSupportActionBar().hide();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle savedInstanceState) {
        super.onSaveInstanceState(savedInstanceState);
        savedInstanceState.putParcelable(STATE_URI, mUri);
        savedInstanceState.putInt(STATE_PAGE, mPage);
        savedInstanceState.putFloat(STATE_ZOOM_RATIO, mZoomRatio);
        savedInstanceState.putInt(STATE_DOCUMENT_ORIENTATION_DEGREES, mDocumentOrientationDegrees);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent resultData) {
        super.onActivityResult(requestCode, resultCode, resultData);

        if (requestCode == ACTION_OPEN_DOCUMENT_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (resultData != null) {
                mUri = resultData.getData();
                mPage = 1;
                mDocumentProperties = null;
                loadPdf();
                invalidateOptionsMenu();
            }
        }
    }

    private void showPageNumber() {
        if (mToast != null) {
            mToast.cancel();
        }
        mTextView.setText(String.format("%s/%s", mPage, mNumPages));
        mToast = new Toast(getApplicationContext());
        mToast.setGravity(Gravity.BOTTOM | Gravity.END, PADDING, PADDING);
        mToast.setDuration(Toast.LENGTH_SHORT);
        mToast.setView(mTextView);
        mToast.show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.pdf_viewer, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        final int[] ids = { R.id.action_zoom_in, R.id.action_zoom_out, R.id.action_jump_to_page,
                R.id.action_next, R.id.action_previous, R.id.action_first, R.id.action_last,
                R.id.action_rotate_clockwise, R.id.action_rotate_counterclockwise,
                R.id.action_view_document_properties };
        if (mDocumentState < STATE_LOADED) {
            for (final int id : ids) {
                final MenuItem item = menu.findItem(id);
                if (item.isVisible()) {
                    item.setVisible(false);
                }
            }
        } else if (mDocumentState == STATE_LOADED) {
            for (final int id : ids) {
                final MenuItem item = menu.findItem(id);
                if (!item.isVisible()) {
                    item.setVisible(true);
                }
            }
            mDocumentState = STATE_END;
        }

        enableDisableMenuItem(menu.findItem(R.id.action_open), getWebViewRelease() >= MIN_WEBVIEW_RELEASE);
        enableDisableMenuItem(menu.findItem(R.id.action_zoom_in), mZoomRatio != MAX_ZOOM_RATIO);
        enableDisableMenuItem(menu.findItem(R.id.action_zoom_out), mZoomRatio != MIN_ZOOM_RATIO);
        enableDisableMenuItem(menu.findItem(R.id.action_next), mPage < mNumPages);
        enableDisableMenuItem(menu.findItem(R.id.action_previous), mPage > 1);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == R.id.action_previous) {
            onJumpToPageInDocument(mPage - 1);
            return true;
        } else if (itemId == R.id.action_next) {
            onJumpToPageInDocument(mPage + 1);
            return true;
        } else if (itemId == R.id.action_first) {
            onJumpToPageInDocument(1);
            return true;
        } else if (itemId == R.id.action_last) {
            onJumpToPageInDocument(mNumPages);
            return true;
        } else if (itemId == R.id.action_open) {
            openDocument();
            return true;
        } else if (itemId == R.id.action_zoom_out) {
            zoomOut(0.25f, true);
            return true;
        } else if (itemId == R.id.action_zoom_in) {
            zoomIn(0.25f, true);
            return true;
        } else if (itemId == R.id.action_rotate_clockwise) {
            documentOrientationChanged(90);
            return true;
        } else if (itemId == R.id.action_rotate_counterclockwise) {
            documentOrientationChanged(-90);
            return true;
        } else if (itemId == R.id.action_view_document_properties) {
            DocumentPropertiesFragment
                .newInstance(mDocumentProperties)
                .show(getSupportFragmentManager(), DocumentPropertiesFragment.TAG);
            return true;
        } else if (itemId == R.id.action_jump_to_page) {
            new JumpToPageFragment()
                .show(getSupportFragmentManager(), JumpToPageFragment.TAG);
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
