package android.content;
import android.content.res.AssetManager;
import java.io.File;
public class Context {
    private final File files;
    private final AssetManager assets;
    public Context(File files, File assetPath) { this.files = files; this.assets = new AssetManager(assetPath); }
    public File getFilesDir() { return files; }
    public AssetManager getAssets() { return assets; }
}
