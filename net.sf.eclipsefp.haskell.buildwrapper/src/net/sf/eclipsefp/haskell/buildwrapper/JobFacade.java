package net.sf.eclipsefp.haskell.buildwrapper;

import java.util.List;

import net.sf.eclipsefp.haskell.buildwrapper.types.BWTarget;
import net.sf.eclipsefp.haskell.buildwrapper.types.BuildOptions;
import net.sf.eclipsefp.haskell.buildwrapper.types.Location;
import net.sf.eclipsefp.haskell.buildwrapper.types.OccurrencesHandler;
import net.sf.eclipsefp.haskell.buildwrapper.types.OutlineDef;
import net.sf.eclipsefp.haskell.buildwrapper.types.OutlineHandler;
import net.sf.eclipsefp.haskell.buildwrapper.types.ThingAtPointHandler;
import net.sf.eclipsefp.haskell.buildwrapper.util.BWText;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.text.IDocument;
import org.eclipse.osgi.util.NLS;

public class JobFacade  {
	private BWFacade realFacade;
	
	public JobFacade(BWFacade realF){
		realFacade=realF;
	}
	
	public void build(final BuildOptions buildOptions) {
		final String jobNamePrefix = NLS.bind(BWText.job_build, getProject().getName());

	      Job buildJob = new Job (jobNamePrefix) {
	        @Override
	        protected IStatus run(IProgressMonitor monitor) {
	          try {
	            monitor.beginTask(jobNamePrefix, IProgressMonitor.UNKNOWN);
	            realFacade.build(buildOptions);
	          } finally {
	            monitor.done();
	          }
	          return Status.OK_STATUS;
	        }
	      };
	      buildJob.setRule( getProject() );
	      buildJob.setPriority(Job.BUILD);
	      buildJob.schedule();
	}

	public void synchronize(final boolean force) {
		final String jobNamePrefix = NLS.bind(BWText.job_synchronize, getProject().getName());

	      Job buildJob = new Job (jobNamePrefix) {
	        @Override
	        protected IStatus run(IProgressMonitor monitor) {
	          try {
	            monitor.beginTask(jobNamePrefix, IProgressMonitor.UNKNOWN);
	            realFacade.synchronize(force);
	          } finally {
	            monitor.done();
	          }
	          return Status.OK_STATUS;
	        }
	      };
	      buildJob.setRule( getProject() );
	      buildJob.setPriority(Job.BUILD);
	      buildJob.schedule();
	}
	
	public void outline(final IFile f,final OutlineHandler handler){
		final String jobNamePrefix = NLS.bind(BWText.outline_job_name, getProject().getName());

	      Job buildJob = new Job (jobNamePrefix) {
	        @Override
	        protected IStatus run(IProgressMonitor monitor) {
	          try {
	            monitor.beginTask(jobNamePrefix, IProgressMonitor.UNKNOWN);
	            List<OutlineDef> defs=realFacade.outline(f);
	            handler.handleOutline(defs);
	          } finally {
	            monitor.done();
	          }
	          return Status.OK_STATUS;
	        }
	      };
	      buildJob.setRule( getProject() );
	      buildJob.setPriority(Job.INTERACTIVE);
	      buildJob.schedule();
	}
	
	public void updateFromEditor(final IFile f,final IDocument doc,final OutlineHandler handler){
		final String jobNamePrefix = NLS.bind(BWText.editor_job_name, getProject().getName());
	
	    Job buildJob = new Job (jobNamePrefix) {
	      @Override
	      protected IStatus run(IProgressMonitor monitor) {
	        try {
	          monitor.beginTask(jobNamePrefix, IProgressMonitor.UNKNOWN);
	          long t0=System.currentTimeMillis();
	          /*if (doc!=null){
	        	  realFacade.write(f, doc.get()); // the write is done by ScionTokenScanner
	          }*/
	          long t1=System.currentTimeMillis();
	          BuildWrapperPlugin.deleteProblems(f);
	          boolean buildOK= doc!=null
	          	?realFacade.build(new BuildOptions().setOutput(false).setTarget(BWTarget.Target).setConfigure(false).setRecompile(false))
	          			:true;
	          long t2=System.currentTimeMillis();
	          
	          List<OutlineDef> defs=realFacade.outline(f);
	          long t3=System.currentTimeMillis();
	          if (defs.size()>0 && buildOK){
	        	  handler.handleOutline(defs); // avoid removing all outline on error
	          }
	          
	          long t4=System.currentTimeMillis();
	          BuildWrapperPlugin.logInfo("write:"+(t1-t0)+"ms,outline:"+(t3-t2)+"ms,handleroutline:"+(t4-t3)+"ms,build:"+(t2-t1)+"ms");
	        } finally {
	          monitor.done();
	        }
	        return Status.OK_STATUS;
	      }
	    };
	    buildJob.setRule( getProject() );
	    buildJob.setPriority(Job.DECORATE);
	    buildJob.schedule();
	}
	
	public void getOccurrences(final IFile f,final String token,final OccurrencesHandler handler){
		final String jobNamePrefix = NLS.bind(BWText.occurrences_job_name, getProject().getName());
		
	    Job buildJob = new Job (jobNamePrefix) {
	      @Override
	      protected IStatus run(IProgressMonitor monitor) {
	        try {
	          monitor.beginTask(jobNamePrefix, IProgressMonitor.UNKNOWN);
	          handler.handleOccurrences(realFacade.getOccurrences(f, token));
	         
	        } finally {
	          monitor.done();
	        }
	        return Status.OK_STATUS;
	      }
	    };
	    buildJob.setRule( getProject() );
	    buildJob.setPriority(Job.DECORATE);
	    buildJob.schedule();
	}
	
	public void getThingAtPoint(final IFile file,final Location location,
			final boolean qualify, final boolean typed,final ThingAtPointHandler handler){
		final String jobNamePrefix = NLS.bind(BWText.thingatpoint_job_name, getProject().getName());
		
	    Job buildJob = new Job (jobNamePrefix) {
	      @Override
	      protected IStatus run(IProgressMonitor monitor) {
	        try {
	          monitor.beginTask(jobNamePrefix, IProgressMonitor.UNKNOWN);
	          handler.handleThing(realFacade.getThingAtPoint(file, location, qualify, typed));
	         
	        } finally {
	          monitor.done();
	        }
	        return Status.OK_STATUS;
	      }
	    };
	    buildJob.setRule( getProject() );
	    buildJob.setPriority(Job.DECORATE);
	    buildJob.schedule();
	}
	
	public IProject getProject() {
		return realFacade.getProject();
	}

}
