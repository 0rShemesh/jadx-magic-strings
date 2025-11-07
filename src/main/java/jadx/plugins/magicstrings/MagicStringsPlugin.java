/**
 * Main plugin class for JADX Magic Strings plugin.
 * 
 * This plugin extracts and analyzes string constants from decompiled code to find:
 * - Source file references (Java/Kotlin file paths)
 * - Method name candidates (potential method names from strings)
 * - All string constants with their associated methods and classes
 * 
 * The plugin registers a pass to extract strings after decompilation and provides
 * a GUI interface to view and interact with the extracted data.
 * 
 * @author 0rshemesh
 * @license Apache License 2.0
 */
package jadx.plugins.magicstrings;

import jadx.api.plugins.JadxPlugin;
import jadx.api.plugins.JadxPluginContext;
import jadx.api.plugins.JadxPluginInfo;
import jadx.api.plugins.gui.JadxGuiContext;
import jadx.plugins.magicstrings.gui.MagicStringsGui;
import jadx.plugins.magicstrings.pass.ExtractStringsPass;

public class MagicStringsPlugin implements JadxPlugin {
	public static final String PLUGIN_ID = "magic-strings";

	@Override
	public JadxPluginInfo getPluginInfo() {
		JadxPluginInfo info = new JadxPluginInfo(PLUGIN_ID, "Magic Strings",
				"Extract information from string constants (source files, method names)");
		info.setHomepage("https://github.com/0rshemesh/jadx-magic-strings");
		info.setRequiredJadxVersion("1.5.2, r2472");
		return info;
	}

	@Override
	public void init(JadxPluginContext context) {
		context.addPass(new ExtractStringsPass());

		JadxGuiContext guiContext = context.getGuiContext();
		if (guiContext != null) {
			MagicStringsGui gui = new MagicStringsGui(context);
			gui.init(guiContext);
		}
	}
}
