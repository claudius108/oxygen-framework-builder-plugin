xquery version "3.0";

declare namespace file = "http://expath.org/ns/file";

declare variable $pluginInstallDir external;
declare variable $pluginTemplatesDir := file:path-to-native($pluginInstallDir || "/templates");

declare variable $frameworkDir := static-base-uri();
declare variable $frameworkId := file:name($frameworkDir);

(
	file:copy(
		file:path-to-native($pluginInstallDir || "/lib/addon-builder-plugin.jar"),
		file:path-to-native($frameworkDir ||"/java")
	)
	,
	if (not(file:exists(file:path-to-native($frameworkDir || "/addon.xml"))))
	then 
		let $text := file:read-text(file:path-to-native($pluginTemplatesDir || "/addon/addon.xml"))
		let $text := replace($text, "frameworkId", $frameworkId)
		
		return file:write-text(file:path-to-native($frameworkDir || "/addon.xml"), $text)
	else ()
)
