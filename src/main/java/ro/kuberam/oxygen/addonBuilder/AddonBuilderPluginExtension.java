package ro.kuberam.oxygen.addonBuilder;

import java.awt.Dimension;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javafx.embed.swing.JFXPanel;

import javax.swing.JComponent;
import javax.swing.JFrame;

import org.apache.log4j.Logger;

import ro.kuberam.oxygen.addonBuilder.javafx.JavaFXPanel;
import ro.kuberam.oxygen.addonBuilder.javafx.bridges.framework.FrameworkGeneratingBridge;
import ro.kuberam.oxygen.addonBuilder.urlSchemes.CustomProtocolURLHandlerExtension;
import ro.sync.exml.plugin.workspace.WorkspaceAccessPluginExtension;
import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;
import ro.sync.exml.workspace.api.standalone.ToolbarComponentsCustomizer;
import ro.sync.exml.workspace.api.standalone.ToolbarInfo;
import ro.sync.util.URLUtil;

public class AddonBuilderPluginExtension implements WorkspaceAccessPluginExtension {

	/**
	 * Logger for logging.
	 */
	private static final Logger logger = Logger.getLogger(AddonBuilderPluginExtension.class.getName());

	private static StandalonePluginWorkspace pluginWorkspaceAccess;
	public static File pluginInstallDir;
	public static File frameworksDir;
	public static JFrame parentFrame;

	@Override
	public void applicationStarted(final StandalonePluginWorkspace pluginWorkspaceAccess) {

		CustomProtocolURLHandlerExtension.setPluginWorkspaceAccess(pluginWorkspaceAccess);

		setPluginWorkspaceAccess(pluginWorkspaceAccess);

		parentFrame = (JFrame) pluginWorkspaceAccess.getParentFrame();

		pluginInstallDir = AddonBuilderPlugin.getInstance().getDescriptor().getBaseDir();
		logger.debug("pluginInstallDir: " + pluginInstallDir);
		frameworksDir = new File(URLUtil.uncorrect(pluginWorkspaceAccess.getUtilAccess()
				.expandEditorVariables("${frameworksDir}", null)));
		logger.debug("frameworksDir: " + frameworksDir);

		// add plugin's toolbar
		pluginWorkspaceAccess.addToolbarComponentsCustomizer(new ToolbarComponentsCustomizer() {
			public void customizeToolbar(final ToolbarInfo toolbarInfo) {

				String toolbarId = toolbarInfo.getToolbarID();

				if (toolbarId.equals("AddonBuilderToolbar")) {
					List<JComponent> comps = new ArrayList<JComponent>();

					String url = null;
					try {
						url = new URL("file:" + pluginInstallDir + File.separator + "components"
								+ File.separator + "addon-builder-toolbar.html").toExternalForm();
					} catch (MalformedURLException e) {
						e.printStackTrace();
					}

					JFXPanel panel = new JavaFXPanel("", url, new FrameworkGeneratingBridge(),
							"OxygenAddonBuilder");

					panel.setPreferredSize(new Dimension(300, 45));
					comps.add(panel);

					toolbarInfo.setComponents(comps.toArray(new JComponent[0]));

					toolbarInfo.setTitle("Addon Builder Toolbar");
				}
			}
		});
	}

	@Override
	public boolean applicationClosing() {
		return true;
	}

	public static StandalonePluginWorkspace getPluginWorkspaceAccess() {
		return pluginWorkspaceAccess;
	}

	public void setPluginWorkspaceAccess(StandalonePluginWorkspace pluginWorkspaceAccess) {
		this.pluginWorkspaceAccess = pluginWorkspaceAccess;
	}

}