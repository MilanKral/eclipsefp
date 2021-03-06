// Copyright (c) 2003-2005 by Leif Frenzel - see http://leiffrenzel.de
package net.sf.eclipsefp.haskell.core.project;

import java.lang.reflect.InvocationTargetException;
import net.sf.eclipsefp.haskell.core.internal.project.ProjectCreationOperation;
import net.sf.eclipsefp.haskell.core.internal.project.ProjectCreationOperationPDETestCase;
import net.sf.eclipsefp.haskell.core.internal.project.ProjectModelFilesOp;
import net.sf.eclipsefp.haskell.core.preferences.ICorePreferenceNames;
import net.sf.eclipsefp.haskell.util.FileUtil;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;

public class HaskellProjectCreationOperation_PDETest extends
		ProjectCreationOperationPDETestCase {

	@Override
  protected ProjectCreationOperation createOperation() {
    getCorePrefs().putBoolean( ICorePreferenceNames.FOLDERS_IN_NEW_PROJECT, true );
    HaskellProjectCreationOperation result
      = new HaskellProjectCreationOperation( );
    ProjectModelFilesOp op=new ProjectModelFilesOp();
    op.setExecutable( true );
    result.setExtraOperation( op  );
    return result;
  }

  public void testAddsHaskellNature() throws InvocationTargetException,
      InterruptedException, CoreException {
    runOperation();

    IProject prj = getWorkspaceRoot().getProject( PROJECT_NAME );
    assertNotNull( prj.getNature( HaskellNature.NATURE_ID ) );
  }

	public void testCreatesDirectoriesFromPreferences()
      throws InvocationTargetException, InterruptedException {
	  getCorePrefs().put( ICorePreferenceNames.FOLDERS_SRC, "customSrc" );
//	  getCorePrefs().put( ICorePreferenceNames.FOLDERS_OUT, "customOut" );

    runOperation();
    IProject prj = getWorkspaceRoot().getProject( PROJECT_NAME );

    assertValid( prj.getFolder( "customSrc" ) );
    assertValid( prj.getFile( "customSrc/Main.hs" ) );
 //   assertValid( prj.getFolder( "customOut" ) );
  }

	public void testCreatesDescriptorFile() throws InvocationTargetException,
      InterruptedException {
    getCorePrefs().put( ICorePreferenceNames.FOLDERS_SRC, FileUtil.DEFAULT_FOLDER_SRC );
	  runOperation();
    IProject prj = getWorkspaceRoot().getProject( PROJECT_NAME );
//    IFile f = prj.getFile( HaskellProjectManager.HASKELL_PROJECT_DESCRIPTOR );
//    assertValid( f );

    assertValid( prj.getFile( prj.getName() + ".cabal" ) );
    assertValid( prj.getFile( "Setup.hs" ) );
    assertValid( prj.getFile( "src/Main.hs" ) );
  }

//	public void testSetsUpProjectFoldersFromPreferences() throws Exception {
//	  getCorePrefs().put( ICorePreferenceNames.FOLDERS_SRC, "mySrc" );
//	  getCorePrefs().put( ICorePreferenceNames.FOLDERS_OUT, "myOut" );
//	  getCorePrefs().put( ICorePreferenceNames.TARGET_BINARY, "myBinary" );
//	  getCorePrefs().put( ICorePreferenceNames.SELECTED_COMPILER, "null" );

//   runOperation();
//    IProject prj = getWorkspaceRoot().getProject( PROJECT_NAME );
//    IFile f = prj.getFile( HaskellProjectManager.HASKELL_PROJECT_DESCRIPTOR );
//    final String expectedContents = HaskellProjectManager
//        .createDescriptorContent( "mySrc", "null" );
//    assertEquals( expectedContents, readContents( f ) );

//  }

  public void testDoNotCreateFoldersWhenPreferenceDisabled() throws Exception {
    getCorePrefs().putBoolean( ICorePreferenceNames.FOLDERS_IN_NEW_PROJECT, false );

    runOperation();

    IProject prj = getWorkspaceRoot().getProject( PROJECT_NAME );


    assertValid( prj.getFile( prj.getName() + ".cabal" ) );
    assertValid( prj.getFile( "Setup.hs" ) );
    assertValid( prj.getFile( "Main.hs" ) );
    assertNotValid( prj.getFolder( "src" ) );

  }


	// helping methods
	//////////////////

//	private String readContents( final IFile file ) throws Exception {
//    StringBuffer buf = new StringBuffer( 1024 );
//    InputStream input = file.getContents();
//    BufferedReader reader = new BufferedReader( new InputStreamReader( input ) );
//    String line;
//    while( null != ( line = reader.readLine() ) ) {
//      buf.append( line );
//      buf.append( '\n' );
//    }
//    input.close();
//    return buf.toString();
//  }

  private void assertValid( final IResource res ) {
    assertNotNull( res );
    assertTrue( "Resource does not exist", res.exists() );
  }
  private void assertNotValid( final IResource res ) {
    assertNotNull( res );
    assertFalse( "Resource does exist", res.exists() );
  }
}
