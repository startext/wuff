/*
 * wuff
 *
 * Copyright 2014  Andrey Hihlovskiy.
 *
 * See the file "LICENSE" for copying and usage permission.
 */
package org.akhikhl.wuff
import org.apache.commons.io.FilenameUtils
import org.gradle.api.Project
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.xml.sax.InputSource

import java.nio.file.Paths
import java.util.regex.Matcher
/**
 * Eclipse Plugin utils
 *
 * @author akhikhl
 */
final class PluginUtils {

  private static final Logger log = LoggerFactory.getLogger(PluginUtils)

  static final String eclipsePluginMask = /([\da-zA-Z_.-]+?)-((\d+\.)+[\da-zA-Z_.-]*)/
  static final String osgiFrameworkPluginName = 'org.eclipse.osgi'
  static final String equinoxLauncherPluginName = 'org.eclipse.equinox.launcher'

  /**
   * Collects eclipse plugin configuration localization files, 'plugin*.properties'.
   *
   * @param project project being analyzed, not modified.
   * @return list of strings (absolute paths) to plugin configuration localization files
   * or empty list, if no localization files are found.
   */
  static List<String> collectPluginLocalizationFiles(Project project) {
    ([project.projectDir] + project.sourceSets.main.resources.srcDirs).findResult { File dir ->
      if(dir.exists()) {
        File locatizationDir = new File(dir, 'OSGI-INF/l10n')
        // if locatizationDir exists, it will be used by OSGi automatically
        // and there's no need for Bundle-Localization
        if(!locatizationDir.exists()) {
          List<String> localizationFiles = new FileNameFinder().getFileNames(dir.absolutePath, 'plugin*.properties')
          if(localizationFiles) {
            log.info '{}: Found bundle localization files: {}', project.name, localizationFiles
            return localizationFiles
          }
        }
      }
      null
    } ?: []
  }

  /**
   * Collects list of privateLib packages in the given project.
   * The function iterates over files of privateLib configuration in the given project.
   * Each file is treated as zip-tree, from which package names are extracted.
   *
   * @param project project being analyzed, not modified.
   * @return list of strings (java package names).
   */
  static List<String> collectPrivateLibPackages(Project project) {
    Set privatePackages = new LinkedHashSet()
    project.configurations.privateLib.files.each { File lib ->
      project.zipTree(lib).visit { f ->
        if(f.isDirectory()) {
          privatePackages.add(f.path.replace('/', '.').replace('\\', '.'))
        }
      }
    }
    if(privatePackages) {
      log.info 'Packages {} found in privateLib dependencies of the project {}', privatePackages, project.name
    }
    return privatePackages as List
  }

  /**
   * Finds classes in the sources of the project.
   *
   * @param project - the project being analyzed, not modified.
   * @param sourceMasks - list of Ant-style file patterns
   * @return list of qualified names (package.class) of the found classes or empty list, if classes are not found.
   */
  static List<String> findClassesInSources(Project project, String... sourceMasks) {
    Set result = new LinkedHashSet()
    sourceMasks.each { String sourceMask ->
      project.sourceSets.main.allSource.srcDirs.each { File srcDir ->
        project.fileTree(srcDir).include(sourceMask).files.each { File sourceFile ->
          String path = Paths.get(srcDir.absolutePath).relativize(Paths.get(sourceFile.absolutePath)).toString()
          result.add(FilenameUtils.removeExtension(path).replace(File.separator, '.'))
        }
      }
    }
    return result as List
  }

  /**
   * Finds a class in the sources of the project.
   *
   * @param project - the project being analyzed, not modified.
   * @param sourceMasks - list of Ant-style file patterns
   * @return qualified name (package.class) of the found class or null, if class is not found.
   */
  static String findClassInSources(Project project, String... sourceMasks) {
    List classes = findClassesInSources(project, sourceMasks)
    return classes ? classes[0] : null
  }

  /**
   * Finds effective bundle file.
   * If wuff.generateBundleFiles is true, the file is searched in project root.
   * If wuff.generateBundleFiles is false, the file is searched in project root and src/main/resources.
   *
   * @param project project being analyzed (not modified).
   * @param path relative path to user-defined bundle file.
   * @return java.io.File, pointing to user-defined bundle file, or null, if such file does not exist.
   */
  static File findEffectiveBundleFile(Project project, String path) {
    File result
    if(project.effectiveWuff.generateBundleFiles) {
      result = new File(project.projectDir, path)
    } else {
      result = ([project.projectDir] + project.sourceSets.main.resources.srcDirs).findResult { File dir ->
        File f = new File(dir, path)
        f.exists() ? f : null
      }
    }
    if(result != null) {
      log.debug '{}: Effective file: {}', project, result
    }
    return result
  }

  /**
   * Finds effective MANIFEST.MF file.
   *
   * @param project project being analyzed (not modified).
   * @return java.io.File, pointing to MANIFEST.MF, or null, if such file does not exist.
   */
  static File findEffectiveManifestFile(Project project) {
    findEffectiveBundleFile(project, 'META-INF/MANIFEST.MF')
  }

  /**
   * Finds effective plugin.xml file.
   *
   * @param project project being analyzed (not modified).
   * @return java.io.File, pointing to plugin.xml, or null, if such file does not exist.
   */
  static File findEffectivePluginXmlFile(Project project) {
    findEffectiveBundleFile(project, 'plugin.xml')
  }

  /**
   * Finds list of import-packages in the given plugin configuration file, 'plugin.xml'.
   *
   * @param project project being analyzed, not modified.
   * @param pluginConfig could have one of the following types: File, InputSource, InputStream, Reader, String.
   * Should resolve to 'plugin.xml' file in the file system.
   * @return list of qualified names of java-packages imported by elements in 'plugin.xml'.
   */
  static List<String> findImportPackagesInPluginConfigFile(Project project, pluginConfig) {
    log.info 'Analyzing import packages in {}', pluginConfig
    if(!(pluginConfig instanceof Node)) {
      if(!(pluginConfig.getClass() in [File, InputSource, InputStream, Reader, String])) {
        pluginConfig = new File(pluginConfig)
      }
      pluginConfig = new XmlParser().parse(pluginConfig)
    }
    def classes = pluginConfig.extension.'**'.findAll({ it.'@class' })*.'@class' + pluginConfig.extension.'**'.findAll({ it.'@contributorClass' })*.'@contributorClass'
    def packages = classes.findResults {
      int dotPos = it.lastIndexOf('.')
      dotPos >= 0 ? it.substring(0, dotPos) : null
    }.unique(false)
    List importPackages = []
    packages.each { String packageName ->
      String packagePath = packageName.replaceAll(/\./, Matcher.quoteReplacement(File.separator))
      if(project.sourceSets.main.allSource.srcDirs.find { new File(it, packagePath).exists() }) {
        log.info 'Found package {} within {}, no import needed', packageName, project.name
      } else {
        log.info 'Did not find package {} within {}, will be imported', packageName, project.name
        importPackages.add(packageName)
      }
    }
    return importPackages
  }

  /**
   * Finds eclipse plugin customization file, 'plugin_customization.ini'.
   *
   * @param project project being analyzed, not modified.
   * @return java.io.File, pointing to 'plugin_customization.ini', or null, if such file does not exist.
   */
  static File findPluginCustomizationFile(Project project) {
    File result = ([project.projectDir] + project.sourceSets.main.resources.srcDirs).findResult { File dir ->
      File f = new File(dir, 'plugin_customization.ini')
      f.exists() ? f : null
    }
    if(result) {
      log.info '{}: Found eclipse plugin customization: {}', project.name, result
    }
    return result
  }

  static File findPluginIntroHtmlFile(Project project, String language = null) {
    String prefix = language ? "nl/$language/" : ''
    String relPath = "${prefix}intro/welcome.html"
    String relPath2 = "${prefix}intro/welcome.htm"
    File result = ([project.projectDir] + project.sourceSets.main.resources.srcDirs).findResult { File dir ->
      File f = new File(dir, relPath)
      if(!f.exists()) {
        f = new File(dir, relPath2)
      }
      f.exists() ? f : null
    }
    if(result) {
      log.info '{}: Found eclipse plugin intro html: {}', project.name, result
    }
    return result
  }

  static File findPluginSplashFile(Project project) {
    File result = ([project.projectDir] + project.sourceSets.main.resources.srcDirs).findResult { File dir ->
      File f = new File(dir, 'splash.bmp')
      f.exists() ? f : null
    }
    if(result) {
      log.info '{}: Found eclipse plugin splash: {}', project.name, result
    }
    return result
  }

  /**
   * Finds user-defined bundle file.
   * If wuff.generateBundleFiles is true, the file is searched in src/main/bundle.
   * If wuff.generateBundleFiles is false, the file is searched in project root and src/main/resources.
   *
   * @param project project being analyzed (not modified).
   * @param path relative path to user-defined bundle file.
   * @return java.io.File, pointing to user-defined bundle file, or null, if such file does not exist.
   */
  static File findUserBundleFile(Project project, String path) {
    File result = getSourceBundleDirs(project).findResult { File dir ->
      File f = new File(dir, path)
      f.exists() ? f : null
    }
    if(result != null) {
      log.debug '{}: User file: {}', project, result
    }
    return result
  }

  static List<File> findUserLocalizationDirs(Project project) {
    getSourceBundleDirs(project).collectMany { File dir ->
      File f = new File(dir, 'nl')
      f.exists() ? f.listFiles({ it.isDirectory() } as FileFilter) : []
    }
  }

  /**
   * Finds user-defined MANIFEST.MF file.
   *
   * @param project project being analyzed (not modified).
   * @return java.io.File, pointing to MANIFEST.MF, or null, if such file does not exist.
   */
  static File findUserManifestFile(Project project) {
    findUserBundleFile(project, 'META-INF/MANIFEST.MF')
  }

  /**
   * Finds user-defined plugin.xml file.
   *
   * @param project project being analyzed (not modified).
   * @return java.io.File, pointing to plugin.xml, or null, if such file does not exist.
   */
  static File findUserPluginXmlFile(Project project) {
    findUserBundleFile(project, 'plugin.xml')
  }

  static String getEclipseApplicationId(Project project) {
    String result
    if(project.pluginXml) {
      result = project.pluginXml.extension.find({ it.'@point' == 'org.eclipse.core.runtime.applications' })?.'@id'
    }
    if(result) {
      result = "${project.name}.${result}"
    }
    return result
  }

  static String getEclipseIntroId(Project project) {
    String result
    if(project.pluginXml) {
      result = project.pluginXml.extension.find({ it.'@point' == 'org.eclipse.ui.intro' })?.intro?.'@id'
    }
    if(result) {
      result = "${project.name}.$result"
    }
    return result
  }

  static String getEclipseProductId(Project project) {
    String result
    if(project.pluginXml) {
      result = project.pluginXml.extension.find({ it.'@point' == 'org.eclipse.core.runtime.products' })?.'@id'
    }
    if(result) {
      result = "${project.name}.$result"
    }
    return result
  }

  static File getEquinoxLauncherFile(Project project) {
    return project.configurations.runtime.find { getPluginName(it.name) == equinoxLauncherPluginName }
  }

  static File getExtraDir(Project project) {
    new File(project.buildDir, 'extra')
  }

  static File getExtraIntroXmlFile(Project project, String language = null) {
    String prefix = language ? "nl/$language/" : ''
    String relPath = "${prefix}intro/introContent.xml"
    new File(getExtraDir(project), relPath)
  }

  static File getExtraPluginXmlFile(Project project) {
    new File(getExtraDir(project), 'plugin.xml')
  }

  static File getExtraPluginCustomizationFile(Project project) {
    new File(getExtraDir(project), 'plugin_customization.ini')
  }

  static File getOsgiFrameworkFile(Project project) {
    return project.configurations.runtime.find { getPluginName(it.name) == osgiFrameworkPluginName }
  }

  static String getPluginName(String fileName) {
    return fileName.replaceAll(eclipsePluginMask, '$1')
  }

  static File getProductOutputBaseDir(Project project) {
    return new File(project.buildDir, 'output')
  }

  static List<File> getSourceBundleDirs(Project project) {
    if(project.effectiveWuff.generateBundleFiles) {
      def bundleSourceDir = project.effectiveWuff.bundleSourceDir
      if (!(bundleSourceDir instanceof File)) {
        bundleSourceDir = new File(bundleSourceDir)
      }
      if (!bundleSourceDir.isAbsolute()) {
        bundleSourceDir = new File(project.projectDir, bundleSourceDir.getPath())
      }
      return bundleSourceDir.exists() ? [ bundleSourceDir ] : []
    }
    [ project.projectDir ] + project.sourceSets.main.resources.srcDirs
  }

  static File getWrappedLibsDir(Project project) {
    return new File(project.buildDir, 'wrappedLibs')
  }
}
