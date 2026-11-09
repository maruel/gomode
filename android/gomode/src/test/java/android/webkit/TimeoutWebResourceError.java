// Supplies a real WebView error callback value through the package-private platform constructor.
package android.webkit;

public final class TimeoutWebResourceError extends WebResourceError {
    @Override
    public int getErrorCode() {
        return WebViewClient.ERROR_TIMEOUT;
    }

    @Override
    public CharSequence getDescription() {
        return "Timed out";
    }
}
