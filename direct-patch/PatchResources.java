import com.reandroid.apk.ApkModule;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.reandroid.arsc.value.ValueType;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PatchResources {
  private static final String PREFIX = "com.colonelpanic.soundcorepatch.";
  private static final int NAME = android.R.attr.name;

  public static void main(String[] args) throws Exception {
    try (ApkModule apk = ApkModule.loadApkFile(new File(args[0]))) {
      AndroidManifestBlock manifest = apk.getAndroidManifest();
      if (!"com.oceanwing.soundcore".equals(manifest.getPackageName())) {
        throw new IllegalArgumentException("Expected the original Soundcore package");
      }
      ResXmlElement app = manifest.getApplicationElement();
      manifest
          .getActivity("com.oceanwing.soundcore.activity.WelcomeActivity", false)
          .getOrCreateAndroidAttribute("label", android.R.attr.label)
          .setValueAsString("soundcore (custom)");
      app.getOrCreateAndroidAttribute("appComponentFactory", android.R.attr.appComponentFactory)
          .setValueAsString(PREFIX + "ComponentFactory");
      ResXmlElement settings = app.newElement("activity");
      settings
          .getOrCreateAndroidAttribute("name", NAME)
          .setValueAsString(PREFIX + "SettingsActivity");
      settings
          .getOrCreateAndroidAttribute("label", android.R.attr.label)
          .setValueAsString("soundcore (actions)");
      settings
          .getOrCreateAndroidAttribute("exported", android.R.attr.exported)
          .setValueAsBoolean(true);
      ResXmlAttribute theme = settings.getOrCreateAndroidAttribute("theme", android.R.attr.theme);
      theme.setValueType(ValueType.REFERENCE);
      theme.setData(android.R.style.Theme_Material_Light_NoActionBar);
      ResXmlAttribute icon = settings.getOrCreateAndroidAttribute("icon", android.R.attr.icon);
      icon.setValueType(ValueType.REFERENCE);
      icon.setData(android.R.drawable.ic_menu_manage);
      ResXmlElement filter = settings.newElement("intent-filter");
      filter
          .newElement("action")
          .getOrCreateAndroidAttribute("name", NAME)
          .setValueAsString("android.intent.action.MAIN");
      filter
          .newElement("category")
          .getOrCreateAndroidAttribute("name", NAME)
          .setValueAsString("android.intent.category.LAUNCHER");
      ResXmlElement query =
          manifest.getManifestElement().getOrCreateElement("queries").newElement("intent");
      query
          .newElement("action")
          .getOrCreateAndroidAttribute("name", NAME)
          .setValueAsString("android.intent.action.MAIN");
      query
          .newElement("category")
          .getOrCreateAndroidAttribute("name", NAME)
          .setValueAsString("android.intent.category.LAUNCHER");
      manifest.setVersionName(args[2]);
      manifest.setVersionCode(Integer.parseInt(args[3]));
      manifest.setMinSdkVersion(28);
      manifest.refreshFull();
      Path output = Path.of(args[1]);
      Files.createDirectories(output);
      Files.write(output.resolve("AndroidManifest.xml"), manifest.getBytes());
    }
  }
}
