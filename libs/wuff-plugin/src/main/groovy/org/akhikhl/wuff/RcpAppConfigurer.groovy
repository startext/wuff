/*
 * wuff
 *
 * Copyright 2014  Andrey Hihlovskiy.
 *
 * See the file "LICENSE" for copying and usage permission.
 */
package org.akhikhl.wuff

import org.gradle.api.Project

/**
 *
 * @author akhikhl
 */
class RcpAppConfigurer extends EquinoxAppConfigurer {

  private static final launchers = [ "linux" : "shell", "windows" : "windows" ]

  RcpAppConfigurer(Project project) {
    super(project)
  }

  @Override
  protected void configureProducts() {

    project.rcp.products.each { product ->
      String platform = product.platform ?: PlatformConfig.current_os
      String arch = product.arch ?: PlatformConfig.current_arch
      String language = product.language ?: ''
      def archiveFiles = []
      if(product.archiveFile)
        archiveFiles.add product.archiveFile
      if(product.archiveFiles)
        archiveFiles.addAll product.archiveFiles
      if(language)
        project.equinox.product name: "rcp_${platform}_${arch}_$language", launcher: launchers[platform], suffix: "${platform}-${arch}-${language}", platform: platform, arch: arch, language: language, jre: product.jre, archiveFiles: archiveFiles
      else
        project.equinox.product name: "rcp_${platform}_${arch}", launcher: launchers[platform], suffix: "${platform}-${arch}", platform: platform, arch: arch, jre: product.jre, archiveFiles: archiveFiles
    }

    project.equinox.archiveProducts = project.rcp.archiveProducts
    project.equinox.additionalFilesToArchive = project.rcp.additionalFilesToArchive
    project.equinox.launchParameters = project.rcp.launchParameters

    super.configureProducts()
  }

  @Override
  protected void createExtensions() {
    super.createExtensions()
    project.extensions.create('rcp', RcpAppPluginExtension)
  }

  @Override
  protected List<String> getModules() {
    return super.getModules() + [ 'rcpApp' ]
  }
}
