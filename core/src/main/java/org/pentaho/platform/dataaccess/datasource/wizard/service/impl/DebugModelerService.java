/*! ******************************************************************************
 *
 * Pentaho
 *
 * Copyright (C) 2024 by Hitachi Vantara, LLC : http://www.pentaho.com
 *
 * Use of this software is governed by the Business Source License included
 * in the LICENSE.TXT file.
 *
 * Change Date: 2029-07-20
 ******************************************************************************/


package org.pentaho.platform.dataaccess.datasource.wizard.service.impl;

import java.io.File;

import org.pentaho.agilebi.modeler.ModelerWorkspace;
import org.pentaho.agilebi.modeler.gwt.GwtModelerWorkspaceHelper;
import org.pentaho.metadata.model.Domain;
import org.pentaho.metadata.model.LogicalModel;
import org.pentaho.metadata.model.SqlPhysicalModel;
import org.pentaho.metadata.model.concept.types.LocalizedString;
import org.pentaho.platform.api.engine.IApplicationContext;
import org.pentaho.platform.api.engine.IPentahoObjectFactory;
import org.pentaho.platform.api.engine.IPentahoSession;
import org.pentaho.platform.api.engine.ObjectFactoryException;
import org.pentaho.platform.api.repository2.unified.RepositoryFile;
import org.pentaho.platform.dataaccess.datasource.wizard.service.agile.AgileHelper;
import org.pentaho.platform.dataaccess.datasource.wizard.service.impl.utils.PentahoSystemHelper;
import org.pentaho.platform.engine.core.system.PentahoSystem;

/**
 * User: nbaker Date: Jul 16, 2010
 */
public class DebugModelerService extends ModelerService {

  private IPentahoSession getSession() {
    IPentahoSession session = null;
    IPentahoObjectFactory pentahoObjectFactory = PentahoSystem.getObjectFactory();
    if ( pentahoObjectFactory != null ) {
      try {
        session = pentahoObjectFactory.get( IPentahoSession.class, "systemStartupSession", null ); //$NON-NLS-1$
      } catch ( ObjectFactoryException e ) {
        e.printStackTrace();
      }
    }
    return session;
  }

  public String serializeModels( Domain domain, String name ) throws Exception {
    String domainId;
    PentahoSystemHelper.init();
    initKettle();

    try {
      DSWDatasourceServiceImpl datasourceService = new DSWDatasourceServiceImpl();
      ModelerWorkspace model =
        new ModelerWorkspace( new GwtModelerWorkspaceHelper(), datasourceService.getGeoContext() );
      model.setModelName( name );
      model.setDomain( domain );
      String solutionStorage = AgileHelper.getDatasourceSolutionStorage();

      String metadataLocation = "resources" + RepositoryFile.SEPARATOR + "metadata"; //$NON-NLS-1$  //$NON-NLS-2$

      String path = solutionStorage + RepositoryFile.SEPARATOR + metadataLocation + RepositoryFile.SEPARATOR;
      domainId = path + name + ".xmi"; //$NON-NLS-1$ 

      IApplicationContext appContext = PentahoSystem.getApplicationContext();
      if ( appContext != null ) {
        path = PentahoSystem.getApplicationContext().getSolutionPath( path );
      }

      File pathDir = new File( path );
      if ( !pathDir.exists() ) {
        pathDir.mkdirs();
      }

      IPentahoSession session = getSession();

      // Keep a reference to the mondrian catalog
      model.getWorkspaceHelper().populateDomain( model );

      LogicalModel lModel = domain.getLogicalModels().get( 0 );
      String catName = lModel.getName( LocalizedString.DEFAULT_LOCALE );
      lModel.setProperty( "MondrianCatalogRef", catName ); //$NON-NLS-1$

      // Serialize domain to xmi.
      /*
        DISABLED DUE TO USE OF OLD API
      String base = PentahoSystem.getApplicationContext().getSolutionRootPath();
      String parentPath = ActionInfo.buildSolutionPath(solutionStorage, metadataLocation, ""); //$NON-NLS-1$
      int status = repository.publish(base, '/' + parentPath, name + ".xmi", reportXML.getBytes("UTF-8"),
      true); //$NON-NLS-1$ //$NON-NLS-2$
      if (status != ISolutionRepository.FILE_ADD_SUCCESSFUL) {
        throw new RuntimeException("Unable to save to repository. Status: " + status); //$NON-NLS-1$
      }

      // Serialize domain to olap schema.
      lModel = domain.getLogicalModels().get(1);
      MondrianModelExporter exporter = new MondrianModelExporter(lModel, LocalizedString.DEFAULT_LOCALE);
      String mondrianSchema = exporter.createMondrianModelXML();
      Document schemaDoc = DocumentHelper.parseText(mondrianSchema);
      byte[] schemaBytes = schemaDoc.asXML().getBytes("UTF-8"); //$NON-NLS-1$

      status = repository.publish(base, '/' + parentPath, name + ".mondrian.xml", schemaBytes, true); //$NON-NLS-1$  
      if (status != ISolutionRepository.FILE_ADD_SUCCESSFUL) {
        throw new RuntimeException("Unable to save to repository. Status: " + status); //$NON-NLS-1$  
      }

      // Refresh Metadata
      PentahoSystem.publish(session, MetadataPublisher.class.getName());
      */

      // Write this catalog to the default Pentaho DataSource and refresh the cache.
      File file = new File( path + name + ".mondrian.xml" ); //$NON-NLS-1$
      // Need to find a better way to get the connection name instead of using the Id.      
      String catConnectStr =
        "Provider=mondrian;DataSource=\"" + ( (SqlPhysicalModel) domain.getPhysicalModels().get( 0 ) )
          .getId() + "\""; //$NON-NLS-1$
      String catDef = "solution:" + solutionStorage + RepositoryFile.SEPARATOR //$NON-NLS-1$
        + "resources" + RepositoryFile.SEPARATOR + "metadata" + RepositoryFile.SEPARATOR + file
        .getName(); //$NON-NLS-1$//$NON-NLS-2$
      addCatalog( catName, catConnectStr, catDef, session );


    } catch ( Exception e ) {
      getLogger().error( e );
      throw e;
    }
    return domainId;
  }

  private void addCatalog( String catName, String catConnectStr, String catDef, IPentahoSession session ) {
    // Do nothing.
  }
}
