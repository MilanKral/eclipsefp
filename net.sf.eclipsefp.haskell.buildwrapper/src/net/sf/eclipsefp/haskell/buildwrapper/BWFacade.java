/**
 *  Copyright (c) 2012 by JP Moresmau
 * This code is made available under the terms of the Eclipse Public License,
 * version 1.0 (EPL). See http://www.eclipse.org/legal/epl-v10.html
 */
package net.sf.eclipsefp.haskell.buildwrapper;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import net.sf.eclipsefp.haskell.buildwrapper.types.BWFileInfo;
import net.sf.eclipsefp.haskell.buildwrapper.types.BWProcessInfo;
import net.sf.eclipsefp.haskell.buildwrapper.types.BWProcessManager;
import net.sf.eclipsefp.haskell.buildwrapper.types.BuildFlags;
import net.sf.eclipsefp.haskell.buildwrapper.types.BuildOptions;
import net.sf.eclipsefp.haskell.buildwrapper.types.CabalImplDetails;
import net.sf.eclipsefp.haskell.buildwrapper.types.CabalMessages;
import net.sf.eclipsefp.haskell.buildwrapper.types.CabalPackage;
import net.sf.eclipsefp.haskell.buildwrapper.types.Component;
import net.sf.eclipsefp.haskell.buildwrapper.types.Component.ComponentType;
import net.sf.eclipsefp.haskell.buildwrapper.types.EvalResult;
import net.sf.eclipsefp.haskell.buildwrapper.types.ImportClean;
import net.sf.eclipsefp.haskell.buildwrapper.types.Location;
import net.sf.eclipsefp.haskell.buildwrapper.types.NameDef;
import net.sf.eclipsefp.haskell.buildwrapper.types.Note;
import net.sf.eclipsefp.haskell.buildwrapper.types.Note.Kind;
import net.sf.eclipsefp.haskell.buildwrapper.types.Occurrence;
import net.sf.eclipsefp.haskell.buildwrapper.types.OutlineDef;
import net.sf.eclipsefp.haskell.buildwrapper.types.OutlineResult;
import net.sf.eclipsefp.haskell.buildwrapper.types.ThingAtPoint;
import net.sf.eclipsefp.haskell.buildwrapper.types.TokenDef;
import net.sf.eclipsefp.haskell.buildwrapper.usage.UsageAPI;
import net.sf.eclipsefp.haskell.buildwrapper.util.BWText;
import net.sf.eclipsefp.haskell.util.FileUtil;
import net.sf.eclipsefp.haskell.util.LangUtil;
import net.sf.eclipsefp.haskell.util.OutputWriter;
import net.sf.eclipsefp.haskell.util.PlatformUtil;
import net.sf.eclipsefp.haskell.util.SingleJobQueue;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.text.IDocument;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * API facade to buildwrapper: exposes all operations and calls the build wrapper executable
 * @author JP Moresmau
 *
 */
public class BWFacade {
	public static final String DIST_FOLDER=".dist-buildwrapper";
	public static final String DIST_FOLDER_CABAL=DIST_FOLDER+"/dist";
	public static final String DIST_FOLDER_CABALDEV=DIST_FOLDER+"/cabal-dev";
	public static final String DIST_FOLDER_SANDBOX=DIST_FOLDER+"/sandbox";
	
	private static final String prefix="build-wrapper-json:";
	
	private static boolean showedNoExeError=false; 
	
	private static AtomicLong poll=new AtomicLong(0);
	
	private int configureFailures=0;
	private String bwPath;
	//private String tempFolder=".dist-buildwrapper";
	
	private CabalImplDetails cabalImplDetails;
	
	private String cabalFile;
	
	private String cabalShortName;
	
	private String flags;
	private List<String> extraOpts=new LinkedList<>();
	
	private File workingDir;
	
	private Writer outStream;
	
	private IProject project;
	
	private OutputWriter ow;
	
	private List<Component> components;
	private Map<String, CabalPackage[]> packageDB;
	
	private boolean hasCabalChanged = false;
	
	private Long lastBuild = null;
	private Set<IProject> addSourceProjects = new HashSet<>();
	
	/**
	 * use a long running buildwrapper process?
	 */
	public static boolean longRunning=true;
	
	/**
	 * log build times
	 */
	public static final boolean logBuildTimes=false;
	
	private BWProcessManager processManager=new BWProcessManager();
	
	/**
	 * the progress monitor for the current thread, so we don't need to pass it everywhere
	 */
	 private static ThreadLocal<IProgressMonitor> monitor=new ThreadLocal<>();
	
	/**
	 * where ever we come from, we only launch one build operation at a time, and lose the intermediate operations
	 */
	private SingleJobQueue buildJobQueue=new SingleJobQueue();

	/**
	 * where ever we come from, we only launch one synchronize operation at a time, and lose the intermediate operations
	 */
	private SingleJobQueue synchronizeJobQueue=new SingleJobQueue();
	
	/**
	 * where ever we come from, we only launch one synchronize operation per file at a time, and lose the intermediate operations
	 */
	private Map<IFile,SingleJobQueue> syncEditorJobQueue=new HashMap<>();

	/**
	 * query for thing at point for a given file, so that we never have more than two jobs at one time
	 */
	private Map<IFile,SingleJobQueue> tapQueuesByFiles=new HashMap<>();
	
	/**
	 * map of outlines for files (key is relative path)
	 */
	private Map<String,OutlineResult> outlines=new HashMap<>();

	/**
	 * map of flag info for files
	 */
	//private Map<IFile, BuildFlagInfo> flagInfos=new HashMap<IFile, BuildFlagInfo>();
	
	private Map<IFile,String> moduleCache=Collections.synchronizedMap(new HashMap<IFile,String>());
	
	/**
	 * do we need to set derived on dist dir?
	 */
	private boolean needSetDerivedOnDistDir=false;
	
	private boolean hasCabalProblems=false;
	
	public SingleJobQueue getBuildJobQueue() {
		return buildJobQueue;
	}
	
	/**
	 * @return the synchronizeJobQueue
	 */
	public SingleJobQueue getSynchronizeJobQueue() {
		return synchronizeJobQueue;
	}
	
	public synchronized SingleJobQueue getThingAtPointJobQueue(IFile f){
		SingleJobQueue sjq=tapQueuesByFiles.get(f);
		if (sjq==null){
			sjq=new SingleJobQueue();
			tapQueuesByFiles.put(f, sjq);
		}
		return sjq;
	}
	
	public synchronized Collection<SingleJobQueue> getThingAtPointJobQueues(){
		return tapQueuesByFiles.values();
	}
	
	/**
	 * do we have a editor synchronize queue (ie have we synchronized with the editor during that session already)
	 * @param f
	 * @return
	 */
	public synchronized boolean hasEditorSynchronizeQueue(IFile f){
		return syncEditorJobQueue.containsKey(f);
	}
	
	public synchronized SingleJobQueue getEditorSynchronizeQueue(IFile f){
		SingleJobQueue sjq=syncEditorJobQueue.get(f);
	    if (sjq==null){
	    	sjq=new SingleJobQueue();
	    	syncEditorJobQueue.put(f,sjq);
	    }
	    return sjq;
	}
	
	public synchronized Collection<SingleJobQueue> getEditorSynchronizeJobQueues(){
		return syncEditorJobQueue.values();
	}
	
	
	private void deleteCabalProblems(){
		String relCabal=getCabalFile().substring(getProject().getLocation().toOSString().length());
		IFile f=getProject().getFile(relCabal);
		if (f!=null && f.exists()){
			BuildWrapperPlugin.deleteProblems(f);
		}
	}
	
	public JSONArray build(BuildOptions buildOptions){
		JSONArray arrC=null;
		if (buildOptions.isConfigure()){
			arrC=configure(buildOptions);
			if (!isOK(arrC)){
				return arrC;
			}
		}
		
		if (hasCabalProblems){
			deleteCabalProblems();
			hasCabalProblems=false;
		}

		LinkedList<String> command=new LinkedList<>();
		command.add("build");
		command.add("--output="+buildOptions.isOutput());
		command.add("--cabaltarget="+buildOptions.getTarget().toString());
		lastBuild=System.currentTimeMillis();
		JSONArray arr=run(command,ARRAY,false);
		refreshDist(true);
		if (arr!=null && arrC!=null){
			for (int a=0;a<arrC.length();a++){
				try {
					arr.put(a, arrC.get(a));
				} catch (JSONException je){
					BuildWrapperPlugin.logError(BWText.process_parse_note_error, je);
				}
			}
		}
		
		return arr;
	}
	
	/**
	 * parse the build result JSON structure, adding markers on impacted files
	 * @param arr the build result as a JSON array
	 * @return was the build successful?
	 */
	public boolean parseBuildResult(JSONArray arr){
		if (arr!=null && arr.length()>1){
			Set<IResource> ress=new HashSet<>();
			JSONObject obj=arr.optJSONObject(0);
			if (obj!=null){
				JSONArray files=obj.optJSONArray("fps");
				if (files!=null){
					for (int a=0;a<files.length();a++){
						String s=files.optString(a);
						if (s!=null && s.length()>0){
							final IResource res=findMember(project,s);
							if (res!=null){
								ress.add(res);
								BuildWrapperPlugin.deleteProblems(res);
							} 
						}
					}
				}
			}
			
			JSONArray notes=arr.optJSONArray(1);
			return parseNotes(notes,ress,null,null);
		}
		return true;
	}
	
	/**
	 * find a resource in a project or a referencing project
	 * @param p the project
	 * @param s the resource path
	 * @return the resource found or null if not found
	 */
	private static IResource findMember(IProject p,String s){
		IResource res=p.findMember(s);
		// linker errors may have full path
		if (res==null){
			String f=s.replace("\\\\", "\\");
			String pos=p.getLocation().toOSString();
			if (f.startsWith(pos)){
				res=p.findMember(f.substring(pos.length()));
			}
		}
		if (res==null){
			for (IProject pR:p.getReferencingProjects()){
				res=findMember(pR, s);
				if (res!=null){
					return res;
				}
			}
		}
		return res;
	}
	
//	private static String escapeFlags(String flag){
//		// not needed any more: we have encoded them in Base 64
//		//flag=flag.replace("\"", "\\\"");
//		return flag;
//	}
	
	/**
	 * Get the editor stanza
	 * @param file
	 * @return
	 */
	public String getEditorStanza(IFile file){
		try {
			String s= file.getPersistentProperty(BuildWrapperPlugin.EDITORSTANZA_PROPERTY);
			if (s!=null){
				return s;
			}
		} catch (CoreException ce){
			BuildWrapperPlugin.logError(ce.getLocalizedMessage(), ce);
		}
		BuildFlags bf=getBuildFlags(file, false);
		if(bf!=null){
			String s=bf.getComponent();
			if (s!=null){
				try {
					file.setPersistentProperty(BuildWrapperPlugin.EDITORSTANZA_PROPERTY,s);
				} catch (CoreException ce){
					BuildWrapperPlugin.logError(ce.getLocalizedMessage(), ce);
				}
				
				return s;
			}
		}
		
		return null;
	}
	
	/**
	 * add editor stanza name to command
	 * @param file
	 * @param command
	 */
	private void addEditorStanza(IFile file,List<String> command){
		String editor=getEditorStanza(file);
		if (editor!=null){
			command.add("--component="+editor);
		}
		
	}
	
	/**
	 * build one file
	 * @param file the file to build the temp contents
	 * @return the names in scope or null if the build failed
	 */
	public Collection<NameDef> build1(IFile file,IDocument d){
		//BuildFlagInfo i=getBuildFlags(file);
		String path=file.getProjectRelativePath().toOSString();
		LinkedList<String> command=new LinkedList<>();
		command.add("build1");
		command.add("--file="+path);
		addEditorStanza(file,command);
		long t0=System.currentTimeMillis();
		//command.add("--buildflags="+escapeFlags(i.getFlags()));
		JSONArray arr=run(command,ARRAY);
		if (logBuildTimes){
			long t1=System.currentTimeMillis();
			BuildWrapperPlugin.logInfo("build:"+(t1-t0)+"ms");
		}
		if (arr!=null && arr.length()>1){
			Set<IResource> ress=new HashSet<>();
			ress.add(file);
			BuildWrapperPlugin.deleteProblems(file);
			JSONArray notes=arr.optJSONArray(1);
			//notes.putAll(i.getNotes());
			Map<IResource,IDocument> m=new HashMap<>();
			m.put(file, d);
			parseNotes(notes,ress,m,null);
			JSONArray names=arr.optJSONArray(0);
			if(names!=null){
				Collection<NameDef> ret=new ArrayList<>();
				for (int a=0;a<names.length();a++){
					try {
						ret.add(new NameDef(names.getJSONObject(a)));
					} catch (JSONException je){
						BuildWrapperPlugin.logError(BWText.process_parse_note_error, je);
					}
				}
				return ret;
			}
			
			
		}
		return null;
	}
	
	private static byte[] contCommand;
	private static byte[] tokenTypesCommand;
	private static byte[] endCommand;
	
	static {
		try {
			contCommand=("r"+PlatformUtil.NL).getBytes(FileUtil.UTF8);
			tokenTypesCommand=("t"+PlatformUtil.NL).getBytes(FileUtil.UTF8);
			endCommand=("q"+PlatformUtil.NL).getBytes(FileUtil.UTF8);
		} catch (UnsupportedEncodingException uee){
			
		}
	}
	
	private BWFileInfo getFileInfo(IFile file){
		return new BWFileInfo(file, getEditorStanza(file));
	}
	
	public Collection<NameDef> build1LongRunning(IFile file,IDocument d,boolean end){
		//BuildFlagInfo i=getBuildFlags(file);
		// avoid "Too late for parseStaticFlags: call it before newSession"
		if (hasCabalChanged){
			getBuildFlags(file);
			hasCabalChanged=false;
		}
		//BuildWrapperPlugin.logInfo("build1LongRunning");
		if (bwPath==null){
			if (!showedNoExeError){
				BuildWrapperPlugin.logError(BWText.error_noexe, null);
				showedNoExeError=true;
			}
			return new ArrayList<>();
		}
		showedNoExeError=false;
		BWFileInfo bfi=getFileInfo(file);
		processManager.startRunningFileWait(bfi);
		JSONArray arr=null;
		//BuildWrapperPlugin.logInfo("build1 longrunning start");
		try {
			BWProcessInfo bpi=processManager.getProcess(bfi);
			Process p=bpi!=null?bpi.getProcess():null;
			if (p==null){
			
				String path=file.getProjectRelativePath().toOSString();
				LinkedList<String> command=new LinkedList<>();
				command.add(bwPath);
				command.add("build1");
				command.add("--file="+path);
				command.add("--longrunning=true");
				command.add("--tempfolder="+DIST_FOLDER);
				command.add("--cabalpath="+cabalImplDetails.getExecutable());
				command.add("--cabalfile="+cabalFile);
				command.add("--cabalflags="+flags);
				for (String s:extraOpts){
					command.add("--cabaloption="+s);
				}
				for (String s:cabalImplDetails.getOptions()){
					command.add("--cabaloption="+s);
				}
				addEditorStanza(file,command);
				ProcessBuilder pb=new ProcessBuilder();
				pb.directory(workingDir);
				pb.redirectErrorStream(true);
				pb.command(command);
				addBuildWrapperPath(pb);
				if (ow!=null && BuildWrapperPlugin.logAnswers) {
					ow.addMessage(LangUtil.join(command, " "));
				}				
				p=pb.start();
				processManager.registerProcess(bfi,p);
			} else {
				if (switchProcess(bpi, bfi, false)){
					p.getOutputStream().write(contCommand);
				}
				
				try {
					p.getOutputStream().flush();
				} catch (IOException ignore){
					// process has died
					processManager.removeProcessForce(bfi);
					end=false; // process already dead
				}
			}
			long t0=System.currentTimeMillis();
			arr=readArrayBW(p);

			// check if process has ended because of some uncaught error in buildwrapper
			if (processManager.hasEnded(bfi)){
				end=false;
			}
						
			if (end && processManager.removeProcess(bfi)){
				p.getOutputStream().write(endCommand);
				try {
					p.getOutputStream().flush();
				} catch (IOException ignore){
					// noop: flush fails if the process closed properly because write flush
				}
			}
			if (logBuildTimes){
				long t1=System.currentTimeMillis();
				BuildWrapperPlugin.logInfo("build:"+(t1-t0)+"ms");
			}
		} catch (IOException ioe){
			BuildWrapperPlugin.logError(BWText.process_launch_error, ioe);
			processManager.removeProcessForce(bfi);
		} finally {
			processManager.stopRunningFile(bfi);
			//BuildWrapperPlugin.logInfo("build1 longrunning end");
		}

			
		if (arr!=null && arr.length()>1){
			Set<IResource> ress=new HashSet<>();
			ress.add(file);
			BuildWrapperPlugin.deleteProblems(file);
			JSONArray notes=arr.optJSONArray(1);
			//notes.putAll(i.getNotes());
			Map<IResource,IDocument> m=new HashMap<>();
			m.put(file, d);
			parseNotes(notes,ress,m,null);
			JSONArray names=arr.optJSONArray(0);
			if(names!=null){
				Collection<NameDef> ret=new ArrayList<>();
				for (int a=0;a<names.length();a++){
					try {
						ret.add(new NameDef(names.getJSONObject(a)));
					} catch (JSONException je){
						BuildWrapperPlugin.logError(BWText.process_parse_note_error, je);
					}
				}
				return ret;
			}
			
			 
		}

		return null;
	}
	
	private boolean switchProcess(BWProcessInfo bpi,BWFileInfo bfi,boolean readResults) throws IOException{
		if (bfi.getFile().equals(bpi.getCurrentFile())){
			return true;
		}
		bpi.setCurrentFile(bfi.getFile());
		bpi.getFiles().add(bfi.getFile());
		String path=bfi.getFile().getProjectRelativePath().toOSString();
		String m=getModuleName(bfi);
		
		String expression="(\""+path+"\",\""+m+"\")";
		String command="c"+expression;
		Process p=bpi.getProcess();
		p.getOutputStream().write((command+PlatformUtil.NL).getBytes(FileUtil.UTF8));
		p.getOutputStream().flush();
		if (readResults){
			parseBuildResult(readArrayBW(p));
		}
		return false;
	}
	
	
	private String getModuleName(BWFileInfo bfi){
		String m=moduleCache.get(bfi.getFile());
		if (m==null){
			m="";
			BuildFlags bf=getBuildFlags(bfi.getFile());
			if (bf!=null){
				m= bf.getModule();
			}
			moduleCache.put(bfi.getFile(), m);
		}
        return m;
	}
	
	private JSONArray readArrayBW(Process p) throws IOException{
		processManager.registerProcess(p);
		try {
			JSONArray arr=null;
			BufferedReader br=new BufferedReader(new InputStreamReader(p.getInputStream(),FileUtil.UTF8));
			//long t0=System.currentTimeMillis();
			String l=br.readLine();
			boolean goOn=true;
	
			while (goOn && l!=null){
				if (l.startsWith(prefix)){
					if (ow!=null && BuildWrapperPlugin.logAnswers) {
						ow.addMessage(l);
					}					
					String jsons=l.substring(prefix.length()).trim();
					try {
						arr=ARRAY.fromJSON(jsons);
					} catch (JSONException je){
						BuildWrapperPlugin.logError(BWText.process_parse_error, je);
					}
					goOn=false;
				} else {
					if (ow!=null) {
						ow.addMessage(l);
					}
	
					l=br.readLine();
				}
			}
			return arr;
		} finally {
			processManager.unregisterProcess();
		}
	}
	
	/**
	 * end long running build process if present
	 * @param file
	 */
	public void endLongRunning(IFile file){
		BWFileInfo bfi=getFileInfo(file);
		BWProcessInfo bpi=processManager.getProcess(bfi);
		if (bpi!=null && processManager.removeProcess(bfi)){
			processManager.startRunningFileWait(bfi);
			try {
				endProcess(bpi.getProcess());
			} finally {
				processManager.stopRunningFile(bfi);
			}
		}
		
	}
	
	public static void endProcess(Process p){
		try {
			p.getOutputStream().write(endCommand);
			try {
				p.getOutputStream().flush();
			} catch (IOException ignore) {
				// noop: flush fails if the process already exited due to write
			}
			try {
				// wait for exit to prevent subsequent file locking issues
				p.waitFor();
			} catch (InterruptedException ignore){
			}
		} catch (IOException ioe){
			BuildWrapperPlugin.logError(BWText.process_launch_error, ioe);
		}
	}
	
//	public BuildFlagInfo getBuildFlags(IFile file){
//		BuildFlagInfo i=flagInfos.get(file);
//		if (i==null){
//			String path=file.getProjectRelativePath().toOSString();
//			LinkedList<String> command=new LinkedList<String>();
//			command.add("getbuildflags");
//			command.add("--file="+path);
//			JSONArray arr=run(command,ARRAY);
//			String s="";
//			JSONArray notes=new JSONArray();
//			if (arr!=null && arr.length()>1){
//				Set<IResource> ress=new HashSet<IResource>();
//				ress.add(file);
//				s=arr.optString(0);
//				notes=arr.optJSONArray(1);
//			}
//			i=new BuildFlagInfo(s, notes);
//			flagInfos.put(file, i);
//		}
//		return i;
//	}
	
	public JSONArray configure(BuildOptions buildOptions){
		parseFlags(); // reset flags in case they have changed
		//BuildWrapperPlugin.deleteProblems(getProject());
		LinkedList<String> command=new LinkedList<>();
		command.add("configure");
		command.add("--cabaltarget="+buildOptions.getTarget().toString());
		JSONArray arr=run(command,ARRAY);
		if (arr!=null && arr.length()>1){
			JSONArray notes=arr.optJSONArray(1);
			return notes;
		}
		return null;
	}
	
	/**
	 * synchronize the project
	 * @param force overwrite files even if source files are not newer
	 */
	public void synchronize(boolean force){
		// if sandbox is missing, cabal operations will fail!
		if (SandboxHelper.isSandboxed(this) && (!SandboxHelper.sandboxExists(this) || !SandboxHelper.referencesSandbox(this))){
			SandboxHelper.install(this);
		}
		
		LinkedList<String> command=new LinkedList<>();
		command.add("synchronize");
		command.add("--force="+force);
		JSONArray arr=run(command,ARRAY);
		boolean ok=true;
		if (arr!=null){
			if(arr.length()>1){
		
				JSONArray notes=arr.optJSONArray(1);
				ok=parseNotes(notes);
			}
			JSONArray allPaths=arr.optJSONArray(0);
			if (allPaths!=null){
				JSONArray paths=allPaths.optJSONArray(0);
				if (paths!=null){
					for (int a=0;a<paths.length();a++){
						try {
							String p=paths.getString(a);
							if (p!=null && p.equals(cabalShortName)){
								cabalFileChanged();
							}
							// remove from cache if file has changed
							outlines.remove(p);
						} catch (JSONException je){
							BuildWrapperPlugin.logError(BWText.process_parse_component_error, je);
						}
					}
				}
				JSONArray dels=allPaths.optJSONArray(1);
				if (dels!=null && BuildWrapperPlugin.getDefault()!=null){
					UsageAPI api=BuildWrapperPlugin.getDefault().getUsageAPI();
					if (api!=null){
						for (int a=0;a<dels.length();a++){
							try {
								String p=dels.getString(a);
								api.removeFile(getProject(), p);
							} catch (JSONException je){
								BuildWrapperPlugin.logError(BWText.process_parse_component_error, je);
							}
						}
					}
				}
			}
		}
		if (ok){
			if (SandboxHelper.isSandboxed(this) && (!SandboxHelper.sandboxExists(this) || !SandboxHelper.referencesSandbox(this))){
				try {
					SandboxHelper.installDeps(this);
				} catch (CoreException ce){
					BuildWrapperPlugin.logError(BWText.error_sandbox,ce);
				}
			}
		}
	}
	
	private void refreshDist(boolean async){
		Runnable r=new Runnable(){
			@Override
			public void run() {
				IFolder fldr=getProject().getFolder(BWFacade.DIST_FOLDER);
				if (fldr!=null && fldr.exists()){
					try {
						fldr.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
					} catch (CoreException ce){
						BuildWrapperPlugin.logError(BWText.error_refreshLocal, ce);
					}
				}
				
			}
		};
		if (async){
			new Thread(r).start();
		
		} else {
			r.run();
		}
	}
	
	public void generateUsage(Component c,boolean returnAll){
		LinkedList<String> command=new LinkedList<>();
		command.add("generateusage");
		command.add("--cabalcomponent="+serializeComponent(c));
		command.add("--returnall="+returnAll);
		JSONArray arr=run(command,ARRAY);
		if (arr!=null){
			// usage is a background process, we don't want to generate markers for it
			/**if(arr.length()>1){
		
				JSONArray notes=arr.optJSONArray(1);
				parseNotes(notes);
			}**/
			JSONArray allPaths=arr.optJSONArray(0);
			if (allPaths!=null){
				if (allPaths.length()>0){
					refreshDist(false);
				}
				BuildWrapperPlugin plugin=BuildWrapperPlugin.getDefault();
				if (plugin!=null){
					for (int a=0;a<allPaths.length();a++){
						try {
							String p=allPaths.getString(a);
							UsageAPI api=plugin.getUsageAPI();
							if (api!=null){
								api.addFile(getProject(),c, p);
							}
						} catch (JSONException je){
							BuildWrapperPlugin.logError(BWText.error_parsing_usage_path, je);
						}
					}
				}
			}
		}
	}
	
	public void cabalFileChanged(){
		components=null;
		packageDB=null;
		hasCabalChanged=true;
		//flagInfos.clear();
	}
	
	public boolean synchronize1(IFile file,boolean force){
		String path=file.getProjectRelativePath().toOSString();
		LinkedList<String> command=new LinkedList<>();
		command.add("synchronize1");
		command.add("--file="+path);
		command.add("--force="+force);
		String s=run(command,STRING);
		return s!=null;
	}
	
	public File write(IFile file,String contents){
		/*String path=file.getProjectRelativePath().toOSString();
		LinkedList<String> command=new LinkedList<String>();
		command.add("write");
		command.add("--file="+path);
		//command.add("--contents="+contents);
		//command.add("--contents=\""+contents.replace("\"", "\\\"")+"\"");
		command.add("--contents="+contents.replace("\"", "\\\""));
		String s=run(command,STRING);
		return s!=null;*/
		try {
			String path=file.getProjectRelativePath().toOSString();
			File tgt=new File(new File(workingDir,DIST_FOLDER),path);
			tgt.getParentFile().mkdirs();
			write(file,tgt, contents);
			return tgt;
		} catch (Exception e){
			BuildWrapperPlugin.logError(e.getLocalizedMessage(), e);
		}
		return null;
	}
	
	public boolean write(IFile file,File tgt,String contents){
		outlines.remove(file.getProjectRelativePath().toOSString());
		try {
			FileUtil.writeSharedFile(tgt, contents, 5);
		} catch (Exception e){
			BuildWrapperPlugin.logError(e.getLocalizedMessage(), e);
		}
		return false;
	}
	
	public List<Component> getComponents(){
		if (components!=null && components.size()>0){
			return components;
		}
		LinkedList<String> command=new LinkedList<>();
		command.add("components");
		JSONArray arr=run(command,ARRAY);
		List<Component> cps=new LinkedList<>();
		if (arr!=null){
			if (arr.length()>1){
		
				JSONArray notes=arr.optJSONArray(1);
				parseNotes(notes);
			}
			JSONArray objs=arr.optJSONArray(0);
			for (int a=0;a<objs.length();a++){
				try {
					cps.add(parseComponent(objs.getJSONObject(a)));
				} catch (JSONException je){
					BuildWrapperPlugin.logError(BWText.process_parse_component_error, je);
				}
			}
		}
		components=cps;
		return components;
	}
	
	public Map<String, CabalPackage[]> getPackagesByDB() {
		if (packageDB!=null){
			return packageDB;
		}
		LinkedList<String> command=new LinkedList<>();
		command.add("dependencies");
		if (SandboxHelper.isSandboxed(this)){
			command.add("--sandbox="+getCabalImplDetails().getSandboxPath());
		}
		JSONArray arr=run(command,ARRAY);
		if (arr==null){
			return new HashMap<>();
		}
		Map<String, CabalPackage[]> cps=new HashMap<>();
		if (arr.length()>1){
			JSONArray notes=arr.optJSONArray(1);
			parseNotes(notes);
		}
		JSONArray objs=arr.optJSONArray(0);
		for (int a=0;a<objs.length();a++){
			try {
				JSONArray arr1=objs.getJSONArray(a);
				String fp=arr1.getString(0);
				JSONArray arr2=arr1.getJSONArray(1);
				CabalPackage[] pkgs=new CabalPackage[arr2.length()];
				for (int b=0;b<arr2.length();b++){
					pkgs[b]=parsePackage(arr2.getJSONObject(b));
				}
				cps.put(fp, pkgs);
			} catch (JSONException je){
				BuildWrapperPlugin.logError(BWText.process_parse_package_error, je);
			}
		}
		packageDB=cps;
		return packageDB;
	}
	
	public OutlineResult outline(IFile file,IDocument d){

		String path=file.getProjectRelativePath().toOSString();
		OutlineResult or=outlines.get(path);
		if (or!=null){
			return or;
		}
		//BuildFlagInfo i=getBuildFlags(file);
		LinkedList<String> command=new LinkedList<>();
		command.add("outline");
		command.add("--file="+path);
		addEditorStanza(file,command);
		//command.add("--buildflags="+escapeFlags(i.getFlags()));
		JSONArray arr=run(command,ARRAY);
		or=new OutlineResult();
		if (arr!=null){
			JSONObject obj=arr.optJSONObject(0);
			if (obj!=null){
				try {
					or=new OutlineResult(file, obj);
				} catch (JSONException je){
					BuildWrapperPlugin.logError(BWText.process_parse_outline_error, je);
				}
			} else {
				// old version pre 0.2.3
				JSONArray objs=arr.optJSONArray(0);
				for (int a=0;a<objs.length();a++){
					try {
						or.getOutlineDefs().add(new OutlineDef(file,objs.getJSONObject(a)));
					} catch (JSONException je){
						BuildWrapperPlugin.logError(BWText.process_parse_outline_error, je);
					}
				}
			}
			
			if (arr.length()>1){
				JSONArray notes=arr.optJSONArray(1);
				//notes.putAll(i.getNotes());
				List<Note> ns=new ArrayList<>();
				Map<IResource,IDocument> m=new HashMap<>();
				m.put(file, d);
				boolean b=parseNotes(notes,null,m,ns);
				or.setNotes(ns);
				or.setBuildOK(b);
			}
			
		}
		registerOutline(file,or);
		return or;
	}
	
	public void registerOutline(IFile file,OutlineResult or){
		String path=file.getProjectRelativePath().toOSString();
		outlines.put(path,or);
	}
	
	public List<TokenDef> tokenTypes(IFile file){
		//long t0=System.currentTimeMillis();
		JSONArray arr=null;
		BWFileInfo bfi=getFileInfo(file);
		if (processManager.startRunningFile(bfi)){
			BWProcessInfo bpi=processManager.getProcess(bfi);
			try {
				if (bpi!=null){
					switchProcess(bpi, bfi, true);
					//BuildWrapperPlugin.logInfo("tokenTypes longrunning start");
					bpi.getProcess().getOutputStream().write(tokenTypesCommand);
					bpi.getProcess().getOutputStream().flush();
					arr=readArrayBW(bpi.getProcess());
				}
				//BuildWrapperPlugin.logInfo("tokenTypes longrunning");
			} catch (IOException ioe){
				//BuildWrapperPlugin.logError(BWText.process_launch_error, ioe);
			} finally {
				//BuildWrapperPlugin.logInfo("tokenTypes longrunning end");
				processManager.stopRunningFile(bfi);
			}
		} 
		if (arr==null){
			String path=file.getProjectRelativePath().toOSString();
			LinkedList<String> command=new LinkedList<>();
			command.add("tokentypes");
			command.add("--file="+path);
			addEditorStanza(file,command);
			arr=run(command,ARRAY);
		}
		//long t01=System.currentTimeMillis();
		List<TokenDef> cps;
		if (arr!=null){
			if (arr.length()>1){
				JSONArray notes=arr.optJSONArray(1);
				parseNotes(notes);
			}
			JSONArray objs=arr.optJSONArray(0);
			cps=new ArrayList<>(objs.length());
			String fn=file.getLocation().toOSString();
			for (int a=0;a<objs.length();a++){
				try {
					cps.add(new TokenDef(fn,objs.getJSONObject(a)));
				} catch (JSONException je){
					BuildWrapperPlugin.logError(BWText.process_parse_outline_error, je);
				}
			}
		} else {
			cps=new ArrayList<>();
		}
		//long t1=System.currentTimeMillis();
		//BuildWrapperPlugin.logInfo("tokenTypes:"+(t1-t0)+"ms, parsing:"+(t1-t01)+"ms");
		return cps;
	}
	
	public List<TokenDef> tokenTypes(String fn){
		//long t0=System.currentTimeMillis();
		LinkedList<String> command=new LinkedList<>();
		command.add("tokentypes");
		command.add("--file="+fn);
		JSONArray arr=run(command,ARRAY);
		//long t01=System.currentTimeMillis();
		List<TokenDef> cps;
		if (arr!=null){
			if (arr.length()>1){
				JSONArray notes=arr.optJSONArray(1);
				parseNotes(notes);
			}
			JSONArray objs=arr.optJSONArray(0);
			cps=new ArrayList<>(objs.length());
			for (int a=0;a<objs.length();a++){
				try {
					cps.add(new TokenDef(fn,objs.getJSONObject(a)));
				} catch (JSONException je){
					BuildWrapperPlugin.logError(BWText.process_parse_outline_error, je);
				}
			}
		} else {
			cps=new ArrayList<>();
		}
		//long t1=System.currentTimeMillis();
		//BuildWrapperPlugin.logInfo("tokenTypes:"+(t1-t0)+"ms, parsing:"+(t1-t01)+"ms");
		return cps;
	}
	
	public BuildFlags getBuildFlags(IFile file){
		return getBuildFlags(file,true);
	}
	
	public BuildFlags getBuildFlags(IFile file,boolean withStanza){
		String path=file.getProjectRelativePath().toOSString();
		LinkedList<String> command=new LinkedList<>();
		command.add("getbuildflags");
		command.add("--file="+path);
		if (withStanza){
			addEditorStanza(file,command);
		}
		JSONArray arr=run(command,ARRAY);
		if (arr!=null){
			if (arr.length()>1){
				JSONArray notes=arr.optJSONArray(1);
				//notes.putAll(i.getNotes());
				parseNotes(notes);
			}
			JSONObject obj=arr.optJSONObject(0);
			if (obj!=null){
				return new BuildFlags(obj);
			}
		}
		return null;
	}
	
	public List<ImportClean> cleanImports(IFile file,boolean format){
		String path=file.getProjectRelativePath().toOSString();
		LinkedList<String> command=new LinkedList<>();
		command.add("cleanimports");
		command.add("--file="+path);
		command.add("--format="+format);
		addEditorStanza(file,command);
		JSONArray arr=run(command,ARRAY);
		List<ImportClean> ret=new ArrayList<>();
		if (arr!=null){
			if (arr.length()>1){
				JSONArray notes=arr.optJSONArray(1);
				//notes.putAll(i.getNotes());
				parseNotes(notes);
			}
			JSONArray locs=arr.optJSONArray(0);
			if (locs!=null){
				for (int a=0;a<locs.length();a++){
					try {
						JSONObject o=locs.getJSONObject(a);
						ret.add(new ImportClean(file,o));
					} catch (JSONException je){
						BuildWrapperPlugin.logError(BWText.process_parse_import_clean_error, je);
					}
				}
			}
		}
		return ret;
	}
	
	public List<Occurrence> getOccurrences(IFile file,String s){
		//BuildFlagInfo i=getBuildFlags(file);
		long t0=System.currentTimeMillis();
		String path=file.getProjectRelativePath().toOSString();
		LinkedList<String> command=new LinkedList<>();
		command.add("occurrences");
		command.add("--file="+path);
		command.add("--token="+s);
		addEditorStanza(file,command);
		//command.add("--buildflags="+escapeFlags(i.getFlags()));
		JSONArray arr=run(command,ARRAY);
		List<Occurrence> cps;
		if (arr!=null){
			if (arr.length()>1){
				JSONArray notes=arr.optJSONArray(1);
				//notes.putAll(i.getNotes());
				parseNotes(notes);
			}
			JSONArray objs=arr.optJSONArray(0);
			cps=new ArrayList<>(objs.length());
			String fn=file.getLocation().toOSString();
			for (int a=0;a<objs.length();a++){
				try {
					TokenDef td=new TokenDef(fn,objs.getJSONObject(a));
					cps.add(new Occurrence(td));
				} catch (JSONException je){
					BuildWrapperPlugin.logError(BWText.process_parse_outline_error, je);
				}
			}
		} else {
			cps=new ArrayList<>();
		}
		if (logBuildTimes){
			long t1=System.currentTimeMillis();
			BuildWrapperPlugin.logInfo("occurrences:"+(t1-t0)+"ms");
		}
		return cps;
	}
	
	public ThingAtPoint getThingAtPoint(IFile file,Location location){
		//BuildFlagInfo i=getBuildFlags(file);
		//long t0=System.currentTimeMillis();
		BWFileInfo bfi=getFileInfo(file);
		JSONArray arr=null;
		if (processManager.startRunningFile(bfi)){
			try {
				BWProcessInfo bpi=processManager.getProcess(bfi);
				if (bpi!=null){
					switchProcess(bpi, bfi, true);
					Process p=bpi.getProcess();
					//BuildWrapperPlugin.logInfo("getThingAtPoint longrunning start");
					String command="p("+location.getStartLine()+","+(location.getStartColumn()+1)+")";
					p.getOutputStream().write((command+PlatformUtil.NL).getBytes(FileUtil.UTF8));
					p.getOutputStream().flush();
					arr=readArrayBW(p);
				}
			} catch (IOException ioe){
				BuildWrapperPlugin.logError(BWText.process_launch_error, ioe);
			} finally {
				//BuildWrapperPlugin.logInfo("getThingAtPoint longrunning end");
				processManager.stopRunningFile(bfi);
			}
		} 
		if (arr==null){
			String path=file.getProjectRelativePath().toOSString();
			LinkedList<String> command=new LinkedList<>();
			command.add("thingatpoint");
			command.add("--file="+path);
			command.add("--line="+location.getStartLine());
			command.add("--column="+(location.getStartColumn()+1));
			addEditorStanza(file,command);
			//command.add("--buildflags="+escapeFlags(i.getFlags()));
			arr=run(command,ARRAY);
		}
		ThingAtPoint tap=null;
		if (arr!=null){
			if (arr.length()>1){
				JSONArray notes=arr.optJSONArray(1);
			//	notes.putAll(i.getNotes());
				parseNotes(notes);
			}
			JSONObject o=arr.optJSONObject(0);
			if (o!=null){
				try {
					tap=new ThingAtPoint(o);
				} catch (JSONException je){
					BuildWrapperPlugin.logError(BWText.process_parse_thingatpoint_error, je);
				}
			}
		}
		//long t1=System.currentTimeMillis();
		//BuildWrapperPlugin.logInfo("getThingAtPoint:"+(t1-t0)+"ms");
		return tap;
	}
	
	/**
	 * get locals from location
	 * @param file
	 * @param location
	 * @return
	 */
	public List<ThingAtPoint> getLocals(IFile file,Location location){
		//long t0=System.currentTimeMillis();
		JSONArray arr=null;
		BWFileInfo bfi=getFileInfo(file);
		//boolean run=false;
		if (processManager.startRunningFile(bfi)){
			try {
				BWProcessInfo bpi=processManager.getProcess(bfi);
				if (bpi!=null){
					switchProcess(bpi, bfi, true);
					Process p=bpi.getProcess();
					//BuildWrapperPlugin.logInfo("getThingAtPoint longrunning start");
					String command="l("+location.getStartLine()+","+(location.getStartColumn()+1)+","+location.getEndLine()+","+(location.getEndColumn()+1)+")";
					p.getOutputStream().write((command+PlatformUtil.NL).getBytes(FileUtil.UTF8));
					p.getOutputStream().flush();
					arr=readArrayBW(p);
					//run=true;
				}
			} catch (IOException ioe){
				BuildWrapperPlugin.logError(BWText.process_launch_error, ioe);
			} finally {
				//BuildWrapperPlugin.logInfo("getThingAtPoint longrunning end");
				processManager.stopRunningFile(bfi);
			}
		} 
		if (arr==null){
			String path=file.getProjectRelativePath().toOSString();
			LinkedList<String> command=new LinkedList<>();
			command.add("locals");
			command.add("--file="+path);
			command.add("--sline="+location.getStartLine());
			command.add("--scolumn="+(location.getStartColumn()+1));
			command.add("--eline="+location.getEndLine());
			command.add("--ecolumn="+(location.getEndColumn()+1));
			addEditorStanza(file,command);
			arr=run(command,ARRAY);
			
		}
		List<ThingAtPoint> taps=new ArrayList<>();
		if (arr!=null){
			if (arr.length()>1){
				JSONArray notes=arr.optJSONArray(1);
			//	notes.putAll(i.getNotes());
				parseNotes(notes);
			}
			JSONArray locs=arr.optJSONArray(0);
			if (locs!=null){
				for (int a=0;a<locs.length();a++){
					try {
						JSONObject o=locs.getJSONObject(a);
						taps.add(new ThingAtPoint(o));
					} catch (JSONException je){
						BuildWrapperPlugin.logError(BWText.process_parse_thingatpoint_error, je);
					}
				}
			}
		}
		//long t1=System.currentTimeMillis();
		//BuildWrapperPlugin.logInfo("getLocals:"+(t1-t0)+"ms ("+taps.size()+","+run+")");
		return taps;
	}
	
	public void clean(boolean everything){
		LinkedList<String> command=new LinkedList<>();
		command.add("clean");
		command.add("--everything="+everything);
		run(command,BOOL);
	}
	
	private boolean parseNotes(JSONArray notes){
		return parseNotes(notes,null,null,null);
	}
	
	private boolean isOK(JSONArray notes){
		if (notes!=null){
			try {
				for (int a=0;a<notes.length();a++){	
					JSONObject o=notes.getJSONObject(a);
					String sk=o.getString("s");
					Kind k="Error".equalsIgnoreCase(sk)?Kind.ERROR:Kind.WARNING;
					if (k.equals(Kind.ERROR)){
						return false;
					}
				}
			} catch (JSONException je){
				BuildWrapperPlugin.logError(BWText.process_parse_note_error, je);
			}
		}
		return true;
	}
	
	/**
	 * parse notes from JSON, either adding them as markers or putting them into the collect
	 * @param notes
	 * @param ress
	 * @param m
	 * @param collect
	 * @return
	 */
	private boolean parseNotes(JSONArray notes,Set<IResource> ress,Map<IResource,IDocument> m,Collection<Note> collect){
		boolean buildOK=true;
		if (notes!=null){
			try {
				if (ress==null){
					ress=new HashSet<>();
				}
				for (int a=0;a<notes.length();a++){
					JSONObject o=notes.getJSONObject(a);
					String sk=o.getString("s");
					Kind k="Error".equalsIgnoreCase(sk)?Kind.ERROR:Kind.WARNING;
					
					JSONObject ol=o.getJSONObject("l");
					String f=ol.getString("f");
					int line=ol.getInt("l");
					int col=ol.getInt("c");
					int endline=ol.getInt("el");
					int endcol=ol.getInt("ec");
					//ow.addMessage("\nParsed location: src="+f+" "+line+":"+col+" to "+endline+":"+endcol+" msg:"+o.getString("t"));
					
					Location loc=new Location(project, f, line, col, endline, endcol);
					//ow.addMessage("\nCreated location: "+loc.getStartLine()+":"+loc.getStartColumn()+" to "+loc.getEndLine()+":"+loc.getEndColumn());
					if (k.equals(Kind.ERROR)){
						buildOK=false;
					}					
					Note n=new Note(k,loc,o.getString("t"),"");
					if (collect!=null){
						collect.add(n);
					} else {
						IResource res=findMember(project,f);
						if (res!=null){
							if (ress.add(res)){
								BuildWrapperPlugin.deleteProblems(res);
							}
							if (res.getLocation().toOSString().equals(getCabalFile())){
								if (isOtherProject(project, n.getMessage())){
									continue;
								}
								hasCabalProblems=true;
							}
							try {
								n.applyAsMarker(res,m!=null?m.get(res):null);
							} catch (CoreException ce){
								BuildWrapperPlugin.logError(BWText.process_apply_note_error, ce);
							}
							
						}
					} 
				}
			} catch (JSONException je){
				BuildWrapperPlugin.logError(BWText.process_parse_note_error, je);
			}
		}
		return buildOK;
	}

	private static boolean isOtherProject(IProject p,String message){
		boolean other=false;
		for (IProject pR:p.getReferencingProjects()){
			if (message.startsWith(pR.getName())){
				return true;
			}
			other=isOtherProject(pR, message);
			if (other){
				return other;
			}
		}
		return other;
	}
	
	private Component parseComponent(JSONObject obj){
		boolean buildable=false;
		try {
			if (obj.has("Library")){
				buildable=obj.getBoolean("Library");
				return new Component(ComponentType.LIBRARY, null, getCabalFile(), buildable);
			} else if (obj.has("Executable")){
				buildable=obj.getBoolean("Executable");
				return new Component(ComponentType.EXECUTABLE, obj.getString("e"), getCabalFile(), buildable);
			} else if (obj.has("TestSuite")){
				buildable=obj.getBoolean("TestSuite");
				return new Component(ComponentType.TESTSUITE, obj.getString("t"), getCabalFile(), buildable);
			} else if (obj.has("Benchmark")){
				buildable=obj.getBoolean("Benchmark");
				return new Component(ComponentType.BENCHMARK, obj.getString("b"), getCabalFile(), buildable);
			}
		} catch (JSONException je){
			BuildWrapperPlugin.logError(BWText.process_parse_component_error, je);
		}
		return null;
	}
	
	private String serializeComponent(Component c) {
	 return ComponentType.LIBRARY.equals(c.getType())?"":c.getName();
	}
	
	private CabalPackage parsePackage(JSONObject obj){
		try {
			String name=obj.getString("n");
			String version=obj.getString("v");
			boolean exposed=obj.getBoolean("e");
			JSONArray comps=obj.getJSONArray("d");
			JSONArray mods=obj.getJSONArray("m");
			
			CabalPackage cp=new CabalPackage();
			cp.setName(name);
			cp.setVersion(version);
			cp.setExposed(exposed);
			for (int a=0;a<mods.length();a++){
				cp.getModules().add(mods.getString(a));
			}
			Component[] deps=new Component[comps.length()];
			for (int a=0;a<comps.length();a++){
				Component c=parseComponent(comps.getJSONObject(a));
				deps[a]=c;
			}
			cp.setComponents(deps);
			return cp;
		} catch (JSONException je){
			BuildWrapperPlugin.logError(BWText.process_parse_package_error, je);
		}
		return null;
	}
	
	/**
	 * flag caching if we need to add the path
	 */
    private Boolean needPath=null;
    /**
     * add the buildwrapper location to the path so that other executables can be found
     * @param pb
     */
	private synchronized void addBuildWrapperPath(ProcessBuilder pb){
		if ((needPath==null || needPath.booleanValue()) && bwPath!=null){
			needPath=false;
			String path=new File(bwPath).getParent();
			if (path!=null){
				Map<String,String> env=pb.environment();
				String pathValue=FileUtil.getPath(env);
				if (Boolean.TRUE.equals(needPath) || pathValue==null || pathValue.length()==0 || !pathValue.contains(path)){
					if (pathValue==null || pathValue.length()==0){
						pathValue=path;
					} else {
						pathValue+=File.pathSeparator+path;
					}
					env.put(FileUtil.getPathVariable(env),pathValue);
					needPath=true;
				} 
			}
		}
	}
	
	private <T> T run(LinkedList<String> args,JSONFactory<T> f){
		return run(args,f,true);
	}
	
	/**
	 * synchronized to avoid concurrent executions of stuff
	 * @param args
	 * @param f
	 * @param canRerun
	 * @return
	 */
	private <T> T run(LinkedList<String> args,JSONFactory<T> f,boolean canRerun){
	
		if (bwPath==null){
			if (!showedNoExeError){
				BuildWrapperPlugin.logError(BWText.error_noexe, null);
				showedNoExeError=true;
			}
			return null;
		}
		if (isCanceled()){
			return null;
		}
		showedNoExeError=false;
		boolean isConfigureAction=args.size()>0 && ("synchronize".equals(args.get(0)) || "configure".equals(args.get(0)) || "build".equals(args.get(0)));
		args.addFirst(bwPath);
		args.add("--tempfolder="+DIST_FOLDER);
		if (cabalImplDetails!=null){
			args.add("--cabalpath="+cabalImplDetails.getExecutable());
		}
		args.add("--cabalfile="+cabalFile);
		args.add("--cabalflags="+flags);
		if (!args.get(1).equals("build")){
			for (String s:extraOpts){
				args.add("--cabaloption="+s);
			}
		}
		if (cabalImplDetails!=null){
			for (String s:cabalImplDetails.getOptions()){
				args.add("--cabaloption="+s);
			}
		}
		if (ow!=null && BuildWrapperPlugin.logAnswers) {
			args.add("--logcabal=true");
		}
		ProcessBuilder pb=new ProcessBuilder();
		pb.directory(workingDir);
		pb.redirectErrorStream(true);
		pb.command(args);
		addBuildWrapperPath(pb);
		if (isCanceled()){
			return null;
		}
		if (ow!=null && BuildWrapperPlugin.logAnswers) {
			ow.addMessage(LangUtil.join(args, " "));
		}					
		T obj=null;
		if (isCanceled()){
			return obj;
		}
		try {
			Process p=pb.start();
			processManager.registerProcess(p);
			
			BufferedReader br=new BufferedReader(new InputStreamReader(p.getInputStream(),FileUtil.UTF8));
			//long t0=System.currentTimeMillis();
			String l=br.readLine();
			boolean goOn=true;
			boolean needConfigure=false;
			boolean needDelete=false;
			while (goOn && l!=null){
				/*if (outStream!=null){
					outStream.write(l);
					outStream.write(PlatformUtil.NL);
					outStream.flush();
				}*/
				
				if (l.startsWith(prefix)){
					if (ow!=null && BuildWrapperPlugin.logAnswers) {
						ow.addMessage(l);
					}					
					String jsons=l.substring(prefix.length()).trim();
					try {
						obj=f.fromJSON(jsons);
					} catch (JSONException je){
						BuildWrapperPlugin.logError(BWText.process_parse_error, je);
					}
					goOn=false;

				} else {
					String ll=l.toLowerCase(Locale.ENGLISH);
					if (ll.contains(CabalMessages.RERUN_CONFIGURE) || ll.contains(CabalMessages.CANNOT_SATISFY)){
						if (ll.contains(CabalMessages.VERSION)){
							needDelete=true;
						}
						needConfigure=true;
					}
					if (ow!=null) {
						ow.addMessage(l);
					}
				}
				if (goOn){
					l=br.readLine();
				}
			}
			processManager.unregisterProcess();
			if (isCanceled()){
				return obj;
			}
			if (needConfigure && canRerun && !isConfigureAction){
				if (needDelete){
					try {
						IFolder fldr=project.getFolder(DIST_FOLDER);
						if (fldr.exists()){
							fldr.delete(IResource.FORCE, new NullProgressMonitor());
						}
					} catch (Throwable ce){
						BuildWrapperPlugin.logError(BWText.process_launch_error, ce);
					}
				}
				configure(new BuildOptions());
				return run(new LinkedList<>(args.subList(1, args.size()-4)),f,false);
			} else if (needDelete){
				configureFailures++;
				if (BuildWrapperPlugin.getMaxConfigureFailures()>=0 && configureFailures>=BuildWrapperPlugin.getMaxConfigureFailures()){
					BuildWrapperPlugin.logError(BWText.error_toomanyfailures, null);
					showedNoExeError=true;
					bwPath=null;
				}
			// we reran and we don't need to configure after that call, we reset the count
			} else if (!canRerun && !needConfigure){
				configureFailures=0;
			}
			// maybe now the folder exists...
			if (needSetDerivedOnDistDir){
				setDerived();
			}
			//long t1=System.currentTimeMillis();
			//BuildWrapperPlugin.logInfo("read run:"+(t1-t0)+"ms");
		} catch (IOException ioe){
			processManager.unregisterProcess();
			if (!isCanceled()){
				BuildWrapperPlugin.logError(BWText.process_launch_error, ioe);
			}
		}
		return obj;
	}

	/**
	 * run cabal with the given argument, using the flags defined on the project
	 * @param args
	 */
	public void runCabal(LinkedList<String> args,File workDir){
		runCabal(args,flags,workDir);
	}
	
	/**
	 * run cabal with the given argument, using the provided flags
	 * @param args 
	 * @param explicitFlags
	 */
	public void runCabal(LinkedList<String> args,String explicitFlags,File workDir){
		if (isCanceled()){
			return;
		}
		String action=args.get(0);
		args.addFirst(cabalImplDetails.getExecutable());
		// we should need flags and extra options ONLY on configure calls...
		if (action.equals("configure")){
			if (explicitFlags!=null && explicitFlags.length()>0){
				args.add("--flags="+explicitFlags);
			}
			for (String s:extraOpts){
				args.add(s);
			}
		}
		for (String s:cabalImplDetails.getOptions()){
			// https://github.com/haskell/cabal/issues/1511
			// sandbox init needs GHC but doesn't accept --with-ghc flag
			// && !action.equals("sandbox")
			 
			if (s.startsWith("--with-ghc") && !action.equals("configure") && !action.equals("install") ){
				continue;
			}
			args.add(s);
		}
		// no we can't build one project in the directory of another
		// because 
		// we pass the build dir as an absolute path otherwise there's confusion, I think cabal-dev launches cabal in the project directory...
		//File bd=new File (workingDir,BWFacade.DIST_FOLDER_CABAL);
		//args.add("--builddir="+BWFacade.DIST_FOLDER_CABAL);
		ProcessBuilder pb=new ProcessBuilder();
		if (workDir==null){
			pb.directory(workingDir);
		} else {
			workDir.mkdirs();
			pb.directory(workDir);
		}
		pb.redirectErrorStream(true);
		pb.command(args);
		addBuildWrapperPath(pb);
		if (ow!=null && BuildWrapperPlugin.logAnswers) {
			ow.addMessage(LangUtil.join(args, " "));
		}					
		if (isCanceled()){
			return;
		}
		try {
			Process p=pb.start();
			processManager.registerProcess(p);
			try {
				BufferedReader br=new BufferedReader(new InputStreamReader(p.getInputStream()));
				//long t0=System.currentTimeMillis();
				String l=br.readLine();
				while (l!=null){
					if (ow!=null) {
						ow.addMessage(l);
					}
					l=br.readLine();
				}
				// maybe now the folder exists...
				if (needSetDerivedOnDistDir){
					setDerived();
				}
			} finally {
				processManager.unregisterProcess();
			}
			
			//long t1=System.currentTimeMillis();
			//BuildWrapperPlugin.logInfo("read run:"+(t1-t0)+"ms");
		} catch (IOException ioe){
			if (!isCanceled()){
				BuildWrapperPlugin.logError(BWText.process_launch_error, ioe);
			}
		}
	}

	
	public OutputWriter getOutputWriter() {
		return ow;
	}
//	public String getTempFolder() {
//		return tempFolder;
//	}
//
//
//	public void setTempFolder(String tempFolder) {
//		this.tempFolder = tempFolder;
//	}



	public String getCabalFile() {
		return cabalFile;
	}


	public void setCabalFile(String cabalFile) {
		this.cabalFile = cabalFile;
		if (this.cabalFile!=null){
			cabalShortName=new File(this.cabalFile).getName();
		}
	}


	public File getWorkingDir() {
		return workingDir;
	}


	public void setWorkingDir(File workingDir) {
		this.workingDir = workingDir;
	}


	public Writer getOutStream() {
		return outStream;
	}

	
	public void setOutStream(Writer outStream) {
		this.outStream = outStream;
		if (ow!=null){
			ow.setTerminate();
		}
		ow=outStream!=null?new OutputWriter("BWFacade.outputWriter: "+project.getName(),outStream) {
			
			@Override
			public void onThrowable(Throwable se) {
				BuildWrapperPlugin.logError(se.getLocalizedMessage(), se);
				
			}
			
			@Override
			public void onIOError(IOException ex) {
				BuildWrapperPlugin.logError(ex.getLocalizedMessage(), ex);
				
			}
		}:null;
		if (ow!=null){
			ow.start();
		}
	}


	public String getBwPath() {
		return bwPath;
	}


	public void setBwPath(String bwPath) {
		this.bwPath = bwPath;
		needPath=null;
	}
	
	private static interface JSONFactory<T>{
		T fromJSON(String json) throws JSONException;
	}
	
	private static JSONFactory<JSONArray> ARRAY=new JSONFactory<JSONArray>() {
		@Override
		public JSONArray fromJSON(String json)throws JSONException {
			return new JSONArray(json);
		}
	};
	
//	private static JSONFactory<JSONObject> OBJECT=new JSONFactory<JSONObject>() {
//		public JSONObject fromJSON(String json)throws JSONException {
//			return new JSONObject(json);
//		}
//	};

	private static JSONFactory<String> STRING=new JSONFactory<String>() {
		@Override
		public String fromJSON(String json)throws JSONException {
			return json;
		}
	};

	
	private static JSONFactory<Boolean> BOOL=new JSONFactory<Boolean>() {
		@Override
		public Boolean fromJSON(String json)throws JSONException {
			return Boolean.valueOf(json);
		}
	};
	
	public IProject getProject() {
		return project;
	}

	public void setProject(IProject project) {
		this.project = project;
		parseFlags();
		setDerived();
	}
	
	/**
	 * wait in a thread for the runnable to finish, or the monitor to be cancelled
	 * @param r the runnable
	 * @param mon the monitor, maybe null
	 */
	public void waitForThread(Runnable r,final IProgressMonitor mon){
		waitForThread(r,mon,0);
	}
	
	/**
	 * wait in a thread for the runnable to finish, or the monitor to be cancelled
	 * or a certain amount of seconds to elapse
	 * @param r the runnable
	 * @param mon the monitor, maybe null
	 * @param maxSecs the maximum running time in seconds (0 or less: no limit)
	 */
	public void waitForThread(Runnable r,final IProgressMonitor mon,final int maxSecs){
		// no monitor, nothing to do
		if (mon==null){
			r.run();
		} else {
			// register the monitor for the current thread
			final Thread jobThread=Thread.currentThread();
			monitor.set(mon);
			//  should the polling thread continue?
			final AtomicBoolean doPoll=new AtomicBoolean(true);
			try {
				final long t0=System.currentTimeMillis();
				final long max=maxSecs>0?t0+(maxSecs*1000):Long.MAX_VALUE;
				//BuildWrapperPlugin.logInfo("maxSecs:"+maxSecs+",t0:"+t0+",max:"+max);
				// the runnable checking if the monitor has been canceled
				Runnable check=new Runnable(){
					@Override
					public void run() {
						//BuildWrapperPlugin.logInfo("start poll");
						while (doPoll.get()){
							try {
								Thread.sleep(1000); // wait a second
							} catch (InterruptedException ie){
								
							}
							boolean expired=System.currentTimeMillis()>max;
							if (expired){
								//BuildWrapperPlugin.logInfo(String.valueOf(System.currentTimeMillis()));
								BuildWrapperPlugin.logWarning(BWText.process_expired, null);
							}
							// canceled!
							if (mon.isCanceled() || expired ){
								//BuildWrapperPlugin.logInfo("canceled");
								// do we have a process?
								Process p=processManager.unregisterProcess(jobThread);
								if (p!=null){
									//buildProcesses.values().remove(p);
									
									//BuildWrapperPlugin.logInfo("destroy");
									p.destroy(); // destroy the process
								}
								return;
							}
						}
						//BuildWrapperPlugin.logInfo("end poll");
					}
				};
				// start polling thread
				Thread t=new Thread(check,"PollingThread-"+(poll.getAndIncrement()));
				t.setDaemon(true);
				t.start();
				// run the runnable in the current thread, otherwise we get into deadlock (the job waits for the thread, the thread does something that requires a lock on the object tat the job is on)
				r.run();
			} finally {
				// stop polling
				doPoll.set(false);
				// remove monitor
				monitor.set(null);
			}
		}
	}
	
	/**
	 * clean: delete the .dist-buildwrapper folder, and synchronize the full content
	 * @param mon the progress monitor
	 * @throws CoreException
	 */
	public void clean(final IProgressMonitor mon) throws CoreException{
		if (project!=null){
			closeAllProcesses();
			if (mon!=null && mon.isCanceled()){
				return;
			}
			/**
			 * closes processes so they don't have locks on resources we'd like to delete
			 * and they can then be restarted with the newly generated files
			 */
			Runnable r1=new Runnable(){
				@Override
				public void run() {
					clean(true);
				}
			};
			waitForThread(r1, mon);
			project.refreshLocal(IResource.DEPTH_ONE, mon);
			setDerived();
			if (mon!=null && mon.isCanceled()){
				return;
			}
			deleteCabalProblems();
			BuildWrapperPlugin.deleteAllProblems(project);
			cabalFileChanged();
			if (mon!=null && mon.isCanceled()){
				return;
			}
			outlines.clear();
			// why does clean recalculate dependencies?
			// if the sandbox is inside the project...
			if (getCabalImplDetails().isSandboxed() && !getCabalImplDetails().isUniqueSandbox()){
				Runnable r2=new Runnable(){
					@Override
					public void run() {
							try {
								SandboxHelper.installDeps(BWFacade.this);
							} catch (CoreException ce){
								BuildWrapperPlugin.logError(BWText.error_sandbox,ce);
							}
						
					}
				};
				waitForThread(r2, mon);
			}
			
			Runnable r3=new Runnable(){
				@Override
				public void run() {
					synchronize(false);
				}
			};
			waitForThread(r3, mon);

		}
	}
	
	/**
	 * close all long running processes
	 */
	public void closeAllProcesses(){
		processManager.closeAll();
	}
	
	public void cleanGenerated(){
		clean(false);
	}
	
	/**
	 * set dist folder as derived so that it will be ignored in searches, etc.
	 */
	private void setDerived(){
		if (project!=null){
			IFolder fldr=project.getFolder(DIST_FOLDER);
			if (fldr.exists()){
				if (!fldr.isDerived()){
					if (!project.getWorkspace().isTreeLocked()){
						try {
							fldr.setDerived(true,new NullProgressMonitor());
							needSetDerivedOnDistDir=false; // ok
						} catch (CoreException ce){
							// log error and leave flag to false, let's hope it'll be better at next run
							BuildWrapperPlugin.logError(BWText.error_derived, ce);
							needSetDerivedOnDistDir=false; // let's not cause errors again
						}
					} else {
						needSetDerivedOnDistDir=true; // tree is locked, le's try again when it's not
					}
				} else {
					needSetDerivedOnDistDir=false; // folder is already marked
				}
			} else {
				needSetDerivedOnDistDir=true; // folder doesn't exist, let's try again when it does
			}
		}
	}
	
	/**
	 * Return the flags parameter to cabal configure
	 * @return the flags
	 */
	public String getFlags() {
		return flags;
	}
	
	/**
	 * Return the extra options passed to cabal configure
	 * @return the extraOpts
	 */
	public List<String> getExtraOpts() {
		return extraOpts;
	}
	
	private void parseFlags(){
		try {
			String currentProp=project.getPersistentProperty( BuildWrapperPlugin.USERFLAGS_PROPERTY );
	        JSONObject flagO=new JSONObject();
	        if (currentProp!=null && currentProp.length()>0){
	          flagO=new JSONObject( currentProp );
	        }
	        StringBuilder sb=new StringBuilder();
	        String sep="";
	        for (Iterator<String> it=flagO.keys();it.hasNext();){
	        	String s=it.next();
	        	boolean b=flagO.getBoolean(s);
	        	sb.append(sep);
	        	sep=" ";
	        	if (!b){
	        		sb.append("-");
	        	}
	        	sb.append(s);
	        }
	        flags=sb.toString();
	        
	        extraOpts.clear();
	        currentProp=project.getPersistentProperty( BuildWrapperPlugin.EXTRAOPTS_PROPERTY );
	        JSONArray arrOpts=new JSONArray();
	        if (currentProp!=null && currentProp.length()>0){
	        	arrOpts=new JSONArray( currentProp );
		    }
	        for (int a=0;a<arrOpts.length();a++){
	        	String s=arrOpts.getString(a);
	        	if (s!=null && s.length()>0){
	        		extraOpts.add(s);
	        	}
	        }
	        
		} catch (Exception e){
			BuildWrapperPlugin.logError(BWText.error_gettingFlags, e);
		}
	}

	public boolean isInTempFolder(IResource r){
		if (r.getProject().equals(getProject()) && getProject().getFolder(DIST_FOLDER).getFullPath().isPrefixOf(r.getFullPath())){
			return true;
		}
		return false;
	}


	public CabalImplDetails getCabalImplDetails() {
		return cabalImplDetails;
	}

	public void setCabalImplDetails(CabalImplDetails cabalImplDetails) {
		if (this.cabalImplDetails!=null && cabalImplDetails!=null){
			if (this.cabalImplDetails.getSandboxPath()!=null && cabalImplDetails.getSandboxPath()!=null){
				if (!this.cabalImplDetails.getSandboxPath().equals(cabalImplDetails.getSandboxPath())){
					SandboxHelper.sandboxLocationChanged(this);
				}
			}
		}
		this.cabalImplDetails = cabalImplDetails;
		this.addSourceProjects.clear();
	}

	
	public boolean isCanceled(){
		IProgressMonitor mon=monitor.get();
		if (mon!=null && mon.isCanceled()){
			return true;
		}
		return false;
	}
	
	public File getSandboxPath(){
		if (getCabalImplDetails().isSandboxed()){
			if (getCabalImplDetails().isUniqueSandbox()){
				return new File (getCabalImplDetails().getSandboxPath());
			} else {
				return new File(getProject().getLocation().toOSString(),getCabalImplDetails().getSandboxPath());
			}
		}
		return null;
	}
	
	/**
	 * evaluate an expression
	 * @param file
	 * @param expression
	 * @return a (possibly multiple) list of results
	 */
	public List<EvalResult> eval(IFile file,String expression){
		// avoid "Too late for parseStaticFlags: call it before newSession"
		if (hasCabalChanged){
			getBuildFlags(file);
			hasCabalChanged=false;
		}
		long t0=System.currentTimeMillis();
		expression=expression.replace('\n', ' ').replace('\r',' ');
		JSONArray arr=null;
		BWFileInfo bfi=getFileInfo(file);
		if (processManager.startRunningFile(bfi)){
			try {
				BWProcessInfo bpi=processManager.getProcess(bfi);
				if (bpi!=null){
					switchProcess(bpi, bfi, true);
					Process p=bpi.getProcess();
					//BuildWrapperPlugin.logInfo("getThingAtPoint longrunning start");
					String command="e "+expression;
					p.getOutputStream().write((command+PlatformUtil.NL).getBytes(FileUtil.UTF8));
					p.getOutputStream().flush();
					arr=readArrayBW(p);
				}
			} catch (IOException ioe){
				BuildWrapperPlugin.logError(BWText.process_launch_error, ioe);
				return Collections.emptyList();
			} finally {
				//BuildWrapperPlugin.logInfo("getThingAtPoint longrunning end");
				processManager.stopRunningFile(bfi);
			}
		} 
		if (arr==null){
			String path=file.getProjectRelativePath().toOSString();
			LinkedList<String> command=new LinkedList<>();
			command.add("eval");
			command.add("--file="+path);
			command.add("--expression="+expression);
			addEditorStanza(file,command);
			arr=run(command,ARRAY);
			
		}
		List<EvalResult> ers=new ArrayList<>();
		if (arr!=null){
			JSONArray locs=arr.optJSONArray(0);
			if (locs!=null){
				for (int a=0;a<locs.length();a++){
					try {
						JSONObject o=locs.getJSONObject(a);
						ers.add(new EvalResult(o));
					} catch (JSONException je){
						BuildWrapperPlugin.logError(BWText.process_parse_thingatpoint_error, je);
					}
				}
			}
		}
		if (logBuildTimes){
			long t1=System.currentTimeMillis();
			BuildWrapperPlugin.logInfo("eval:"+(t1-t0)+"ms ("+ers.size()+")");
		}
		return ers;
	}
	
	/**
	 * @return the lastBuild
	 */
	public Long getLastBuild() {
		return lastBuild;
	}
	
	/**
	 * @return the lastAddSource
	 */
	public Set<IProject> getAddSourceProjects() {
		return addSourceProjects;
	}
}
