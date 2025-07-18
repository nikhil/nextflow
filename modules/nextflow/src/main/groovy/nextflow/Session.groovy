/*
 * Copyright 2013-2024, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nextflow

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.Consumer

import com.google.common.hash.HashCode
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.transform.Memoized
import groovy.transform.PackageScope
import groovy.util.logging.Slf4j
import groovyx.gpars.GParsConfig
import groovyx.gpars.dataflow.DataflowWriteChannel
import groovyx.gpars.dataflow.operator.DataflowProcessor
import nextflow.cache.CacheDB
import nextflow.cache.CacheFactory
import nextflow.conda.CondaConfig
import nextflow.config.Manifest
import nextflow.container.ContainerConfig
import nextflow.dag.DAG
import nextflow.exception.AbortOperationException
import nextflow.exception.AbortSignalException
import nextflow.exception.IllegalConfigException
import nextflow.exception.MissingLibraryException
import nextflow.exception.ScriptCompilationException
import nextflow.executor.ExecutorFactory
import nextflow.extension.CH
import nextflow.file.FileHelper
import nextflow.file.FilePorter
import nextflow.plugin.Plugins
import nextflow.processor.ErrorStrategy
import nextflow.processor.TaskFault
import nextflow.processor.TaskHandler
import nextflow.processor.TaskProcessor
import nextflow.script.BaseScript
import nextflow.script.ProcessConfig
import nextflow.script.ProcessFactory
import nextflow.script.ScriptBinding
import nextflow.script.ScriptFile
import nextflow.script.ScriptMeta
import nextflow.script.ScriptRunner
import nextflow.script.WorkflowMetadata
import nextflow.spack.SpackConfig
import nextflow.trace.AnsiLogObserver
import nextflow.trace.TraceObserver
import nextflow.trace.TraceObserverFactory
import nextflow.trace.TraceObserverFactoryV2
import nextflow.trace.TraceObserverV2
import nextflow.trace.TraceRecord
import nextflow.trace.WorkflowStatsObserver
import nextflow.trace.event.FilePublishEvent
import nextflow.trace.event.TaskEvent
import nextflow.trace.event.WorkflowOutputEvent
import nextflow.util.Barrier
import nextflow.util.ConfigHelper
import nextflow.util.Duration
import nextflow.util.HistoryFile
import nextflow.util.LoggerHelper
import nextflow.util.NameGenerator
import nextflow.util.SysHelper
import nextflow.util.ThreadPoolManager
import nextflow.util.Threads
import nextflow.util.VersionNumber
import org.apache.commons.lang.exception.ExceptionUtils
import sun.misc.Signal
import sun.misc.SignalHandler
/**
 * Holds the information on the current execution
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
class Session implements ISession {

    /**
     * Keep a list of all processor created
     */
    final Collection<DataflowProcessor> allOperators = new ConcurrentLinkedQueue<>()

    final List<Closure> igniters = new ArrayList<>(20)

    final Map<String,DataflowWriteChannel> outputs = [:]

    /**
     * Creates process executors
     */
    ExecutorFactory executorFactory

    /**
     * Script binding
     */
    ScriptBinding binding

    /**
     * Holds the configuration object
     */
    Map config

    /**
     * Enable / disable tasks result caching
     */
    boolean cacheable

    /**
     * whenever it has been launched in resume mode
     */
    boolean resumeMode

    /**
     * The folder where workflow outputs are stored
     */
    Path outputDir

    /**
     * The folder where tasks temporary files are stored
     */
    Path workDir

    /**
     * Bucket work directory for cloud based executors
     */
    Path bucketDir

    /**
     * The folder where the main script is contained
     */
    Path baseDir

    /**
     * The pipeline script name (without parent path)
     */
    String scriptName

    /**
     * The main script object
     */
    BaseScript script

    /**
     * Mnemonic name of this run instance
     */
    String runName

    /**
     * Enable stub run mode
     */
    boolean stubRun

    /**
     * Enable preview mode
     */
    boolean preview

    /**
     * Folder(s) containing libs and classes to be added to the classpath
     */
    List<Path> libDir

    /**
     * List files that concurrent on the session configuration
     */
    List<Path> configFiles

    String profile

    String commandLine

    /*
     * Project repository commit ID
     */
    String commitId

    /*
     * Disable the upload of project 'bin' directory when using cloud executor
     */
    boolean disableRemoteBinDir

    /**
     * Suppress all output from pipeline script
     */
    boolean quiet

    /**
     * Enable debugging mode
     */
    boolean debug

    /**
     * Defines the cloud path where store cache meta-data
     */
    Path cloudCachePath

    /**
     * Local path where script generated classes are saved
     */
    private Path classesDir

    private Path binDir

    private Map<String,Path> binEntries = [:]

    /**
     * The unique identifier of this session
     */
    private UUID uniqueId

    private DAG dag

    private CacheDB cache

    private Barrier processesBarrier = new Barrier()

    private Barrier monitorsBarrier = new Barrier()

    private volatile boolean failOnIgnore

    private volatile boolean cancelled

    private volatile boolean aborted

    private volatile boolean terminated

    private volatile ExecutorService execService

    private volatile TaskFault fault

    private volatile Throwable error

    private volatile boolean shutdownInitiated

    private Queue<Runnable> shutdownCallbacks = new ConcurrentLinkedQueue<>()

    private int poolSize

    @Deprecated
    private List<TraceObserver> observersV1 = Collections.emptyList()

    private List<TraceObserverV2> observersV2 = Collections.emptyList()

    private Closure errorAction

    private boolean statsEnabled

    private WorkflowMetadata workflowMetadata

    private WorkflowStatsObserver statsObserver

    private FilePorter filePorter

    boolean getStatsEnabled() { statsEnabled }

    private String dumpHashes

    private List<String> dumpChannels

    String getDumpHashes() { dumpHashes }

    List<String> getDumpChannels() { dumpChannels }

    TaskFault getFault() { fault }

    Throwable getError() { error }

    WorkflowStatsObserver getStatsObserver() { statsObserver }

    WorkflowMetadata getWorkflowMetadata() { workflowMetadata }

    Path getClassesDir() { classesDir }

    ScriptBinding.ParamsMap getParams() { binding.getParams() }

    String resolvedConfig

    boolean ansiLog

    boolean disableJobsCancellation

    AnsiLogObserver ansiLogObserver

    FilePorter getFilePorter() { filePorter }

    /**
     * Creates a new session with an 'empty' (default) configuration
     */
    Session() {
        create(new LinkedHashMap(10))
    }


    /**
     * Create a new session given the configuration specified
     *
     * @param config
     */
    Session(Map obj) {
        final config = obj instanceof ConfigObject ? obj.toMap() : obj
        create(config)
    }

    /**
     * @return The current session {@link UUID}
     */
    UUID getUniqueId() { uniqueId }

    /**
     * @return The session max number of thread allowed
     */
    int getPoolSize() { poolSize }

    CacheDB getCache() { cache }

    /**
     * Creates a new session using the configuration properties provided
     *
     * @param binding
     */
    private void create( Map config ) {
        assert config != null

        this.config = config
        this.dumpHashes = config.dumpHashes
        this.dumpChannels = (List<String>)config.dumpChannels
        this.binding = new ScriptBinding()

        // -- poor man session object dependency injection
        Global.setSession(this)
        Global.setConfig(config)
        // -- init static structs
        NF.init()

        // -- cacheable flag
        cacheable = config.cacheable == null || config.cacheable.toString()=='true'

        // -- sets resumeMode and uniqueId
        if( config.resume ) {
            resumeMode = true
            uniqueId = UUID.fromString(config.resume as String)
        }
        else {
           uniqueId = systemEnv.get('NXF_UUID') ? UUID.fromString(systemEnv.get('NXF_UUID')) : UUID.randomUUID()
        }
        log.debug "Session UUID: $uniqueId"

        // -- set the run name
        this.runName = config.runName ?: NameGenerator.next()
        log.debug "Run name: $runName"

        // -- dry run
        this.stubRun = config.stubRun

        // -- preview
        this.preview = config.preview

        // -- normalize taskConfig object
        if( config.process == null ) config.process = [:]
        if( config.env == null ) config.env = [:]

        if( !config.poolSize ) {
            final cpus = Runtime.getRuntime().availableProcessors()
            config.poolSize = cpus==1 ? 2 : cpus
        }

        // -- set the thread pool size
        this.poolSize = config.poolSize as int
        log.debug "Executor pool size: ${poolSize}"

        // -- DAG object
        this.dag = new DAG()

        // -- init output dir
        this.outputDir = FileHelper.toCanonicalPath(config.outputDir ?: 'results')

        // -- init work dir
        this.workDir = FileHelper.toCanonicalPath(config.workDir ?: 'work')
        this.setLibDir( config.libDir as String )

        // -- init cloud cache path
        this.cloudCachePath = cloudCachePath(config.cloudcache as Map, workDir)

        // -- file porter config
        this.filePorter = new FilePorter(this)

    }

    protected Path cloudCachePath(Map cloudcache, Path workDir) {
        if( !cloudcache?.enabled )
            return null
        final String path = cloudcache.path
        final result = path ? FileHelper.asPath(path) : workDir
        if( result.scheme !in ['s3','az','gs'] ) {
            throw new IllegalArgumentException("Storage path not supported by Cloud-cache - offending value: '${result}'")
        }
        return result
    }

    /**
     * Initialize the session workDir, libDir, baseDir and scriptName variables
     */
    Session init( ScriptFile scriptFile, List<String> args=null ) {

        if(!workDir.mkdirs()) throw new AbortOperationException("Cannot create work-dir: $workDir -- Make sure you have write permissions or specify a different directory by using the `-w` command line option")
        log.debug "Work-dir: ${workDir.toUriString()} [${FileHelper.getPathFsType(workDir)}]"

        if( config.bucketDir ) {
            this.bucketDir = config.bucketDir as Path
            log.debug "Bucket-dir: ${bucketDir.toUriString()}"
        }

        if( scriptFile ) {
            // the folder that contains the main script
            this.setBaseDir(scriptFile.main.parent)
            // set the script name attribute
            this.setScriptName(scriptFile.main.name)
        }

        // set the byte-code target directory
        this.disableRemoteBinDir = getExecConfigProp(null, 'disableRemoteBinDir', false)
        this.classesDir = FileHelper.createLocalDir()
        this.executorFactory = new ExecutorFactory(Plugins.manager)
        this.observersV2 = createObserversV2()
        this.observersV1 = createObserversV1()
        this.statsEnabled = observersV1.any { ob -> ob.enableMetrics() } || observersV2.any { ob -> ob.enableMetrics() }
        this.workflowMetadata = new WorkflowMetadata(this, scriptFile)

        // configure script params
        binding.setParams( (Map)config.params )
        binding.setArgs( new ScriptRunner.ArgsList(args) )

        cache = CacheFactory.create(uniqueId,runName).open()

        return this
    }

    Session setBinding(ScriptBinding binding ) {
        this.binding = binding
        return this
    }

    ProcessFactory newProcessFactory(BaseScript script) {
        new ProcessFactory(script, this)
    }

    /**
     * Create the required trace observers based on the given CLI and config options.
     */
    @PackageScope
    List<TraceObserver> createObserversV1() {
        final result = new ArrayList<TraceObserver>(10)
        for( TraceObserverFactory f : Plugins.getExtensions(TraceObserverFactory) ) {
            log.debug "Observer factory: ${f.class.simpleName}"
            result.addAll(f.create(this))
        }
        return result
    }

    @PackageScope
    List<TraceObserverV2> createObserversV2() {
        final result = new ArrayList<TraceObserverV2>(10)
        this.statsObserver = new WorkflowStatsObserver(this)
        result.add(statsObserver)
        for( TraceObserverFactoryV2 f : Plugins.getExtensions(TraceObserverFactoryV2) ) {
            log.debug "Observer factory (v2): ${f.class.simpleName}"
            result.addAll(f.create(this))
        }
        return result
    }

    /*
     * intercepts interruption signal i.e. CTRL+C
     * - on the first invoke session#abort
     * - on third force termination with System#exit
     */
    private void registerSignalHandlers() {

        int c = 0
        final ctrl_c = { Signal sig ->
            switch( ++c ) {
                case 1: abort(new AbortSignalException(sig)); println ''; break
                case 2: println "One more CTRL+C to force exit"; break
                default: log.info 'Adieu'; System.exit(1)
            }

        } as SignalHandler

        // -- abort session handler
        final abort_h = { Signal sig -> abort(new AbortSignalException(sig)) } as SignalHandler

        // -- register handlers
        Signal.handle( new Signal("INT"), ctrl_c)
        Signal.handle( new Signal("TERM"), abort_h)
        Signal.handle( new Signal("HUP"), abort_h)
    }

    void addIgniter( Closure action )  {
        igniters.add(action)
    }

    void fireDataflowNetwork(boolean preview=false) {
        checkConfig()
        notifyFlowBegin()

        // bridge any dataflow queue into a broadcast channel
        CH.broadcast()

        if( preview ) {
            terminated = true
        }
        else {
            callIgniters()
        }
    }

    private void callIgniters() {
        log.debug "Igniting dataflow network (${igniters.size()})"
        for( Closure action : igniters ) {
            try {
                action.call()
            }
            catch( Exception e ) {
                log.error(e.message ?: "Failed to ignite dataflow network", e)
                abort(e)
                break
            }
        }
    }

    /**
     * Dump the current dataflow network listing
     * the status of active processes and operators
     * for debugging purpose
     */
    String dumpNetworkStatus() {
        try {
            def msg = dag.dumpActiveNodes()
            msg ? "The following nodes are still active:\n" + msg : null
        }
        catch( Exception e ) {
            log.debug "Unexpected error while dumping DAG status", e
            return null
        }
    }

    Session start() {
        log.debug "Session start"

        // register shut-down cleanup hooks
        registerSignalHandlers()

        // create tasks executor
        execService = Threads.useVirtual()
                ? Executors.newVirtualThreadPerTaskExecutor()
                : Executors.newFixedThreadPool(poolSize)

        // signal start to trace observers
        notifyFlowCreate()

        return this
    }

    ScriptBinding getBinding() { binding }

    @Memoized
    ClassLoader getClassLoader() { getClassLoader0() }

    @PackageScope
    ClassLoader getClassLoader0() {
        // extend the class-loader if required
        final gcl = new GroovyClassLoader()
        final libraries = ConfigHelper.resolveClassPaths(getLibDir())

        for( Path lib : libraries ) {
            def path = lib.complete()
            log.debug "Adding to the classpath library: ${path}"
            gcl.addClasspath(path.toString())
        }

        return gcl
    }

    Barrier getBarrier() { monitorsBarrier }

    /**
     * The folder where script binaries file are located, by default the folder 'bin'
     * in the script base directory
     */
    Path getBinDir() { binDir }

    Map<String,Path> getBinEntries() { binEntries ?: Collections.<String,Path>emptyMap() }

    void setBaseDir( Path baseDir ) {
        this.baseDir = baseDir

        def path = baseDir.resolve('bin')
        if( path.exists() && path.isDirectory() ) {
            this.binDir = path
            this.binEntries = findBinEntries(path)
        }
        else {
            log.debug "Script base path does not exist or is not a directory: ${path}"
        }
    }

    protected Map<String,Path> findBinEntries(Path path) {
        Map<String,Path> result = new LinkedHashMap(10)
        path
                .listFiles { file -> Files.isExecutable(file) }
                .each { Path file -> result.put(file.name,file)  }
        return result
    }

    void setLibDir( String str ) {

        if( !str ) return

        def files = str.split( File.pathSeparator ).collect { String it -> Paths.get(it) }
        if( !files ) return

        libDir = []
        for( Path file : files ) {
            if( !file.exists() )
                throw new MissingLibraryException("Cannot find specified library: ${file.complete()}")

            libDir << file
        }
    }

    List<Path> getLibDir() {
        if( libDir )
            return libDir

        libDir = []
        def localLib = baseDir ? baseDir.resolve('lib') : Paths.get('lib')
        if( localLib.exists() ) {
            log.debug "Using default localLib path: $localLib"
            libDir << localLib
        }
        return libDir
    }

    Map getConfigEnv() {
        if( !config.env )
            return Collections.emptyMap()
        if( config.env instanceof Map )
            return new LinkedHashMap((Map)config.env)
        throw new IllegalStateException("Not a valid config env object: $config.env")
    }

    @Memoized
    Manifest getManifest() {
        if( !config.manifest )
            return new Manifest()
        if( config.manifest instanceof Map )
            return new Manifest(config.manifest as Map)
        else {
            log.warn "Invalid config manifest definition [${this.getClass().getName()}]"
            return new Manifest()
        }
    }

    /**
     * Await the termination of all processors
     */
    void await() {
        log.debug "Session await"
        processesBarrier.awaitCompletion()
        log.debug "Session await > all processes finished"
        terminated = true
        monitorsBarrier.awaitCompletion()
        log.debug "Session await > all barriers passed"
        if( !aborted ) {
            joinAllOperators()
            log.trace "Session > all operators finished"
        }
    }

    void destroy() {
        try {
            log.trace "Session > destroying"
            // shutdown thread pools
            finalizePoolManager?.shutdownOrAbort(aborted,this)
            publishPoolManager?.shutdownOrAbort(aborted,this)
            // invoke shutdown callbacks
            shutdown0()
            log.trace "Session > after cleanup"
            // shutdown executors
            executorFactory?.shutdown()
            executorFactory = null
            // shutdown executor service
            execService?.shutdown()
            execService = null
            log.trace "Session > executor shutdown"

            // -- close db
            cache?.close()

            // -- cleanup script classes dir
            classesDir?.deleteDir()
        }
        finally {
            // -- update the history file
            if( !HistoryFile.disabled() && HistoryFile.DEFAULT.exists() ) {
                HistoryFile.DEFAULT.update(runName,isSuccess())
            }
            log.trace "Session destroyed"
        }
    }

    final protected void joinAllOperators() {
        int attempts=0

        while( allOperators.size() ) {
            if( attempts++>0 )
                log.debug "This looks weird, attempt number $attempts to join pending operators"

            final itr = allOperators.iterator()
            while( itr.hasNext() ) {
                final op = itr.next()
                op.join()
                itr.remove()
            }
        }
    }

    final protected void shutdown0() {
        log.trace "Shutdown: $shutdownCallbacks"
        shutdownInitiated = true
        while( shutdownCallbacks.size() ) {
            final hook = shutdownCallbacks.poll()
            try {
                hook.run()
            }
            catch( Exception e ) {
                log.debug "Failed to execute shutdown hook: $hook", e
            }
        }

        // -- invoke observers completion handlers
        notifyFlowComplete()
    }

    /**
     * Halt the pipeline execution choosing exiting immediately or completing current
     * pending task depending the chosen {@link ErrorStrategy}
     *
     * @param fault A {@link TaskFault} instance representing the error that caused the pipeline to stop
     */
    void fault(TaskFault fault, TaskHandler handler=null) {
        if( this.fault ) { return }
        this.fault = fault

        if( fault.task && fault.task.errorAction == ErrorStrategy.FINISH ) {
            cancel(handler)
        }
        else {
            abort(fault.error)
        }
    }

    /**
     * Cancel the pipeline execution waiting for the current running tasks to complete
     */
    @PackageScope
    void cancel(TaskHandler handler) {
        log.info "Execution cancelled -- Finishing pending tasks before exit"
        cancelled = true
        notifyError(handler)
        executorFactory.signalExecutors()
        processesBarrier.forceTermination()
        allOperators *. terminate()
    }

    /**
     * Terminate the pipeline execution killing all running tasks
     *
     * @param cause A {@link Throwable} instance representing the execution that caused the pipeline execution to abort
     */
    void abort(Throwable cause = null) {
        if( aborted ) return
        if( cause !instanceof ScriptCompilationException )
            log.debug "Session aborted -- Cause: ${cause?.message ?: cause ?: '-'}"
        aborted = true
        error = cause
        LoggerHelper.aborted = true
        try {
            // log the dataflow network status
            def status = dumpNetworkStatus()
            if( status )
                log.debug(status)
            // dump threads status
            if( log.isTraceEnabled() )
                log.trace(SysHelper.dumpThreads())
            // force termination
            notifyError(null)
            ansiLogObserver?.forceTermination()
            executorFactory?.signalExecutors()
            processesBarrier.forceTermination()
            monitorsBarrier.forceTermination()
            operatorsForceTermination()
        }
        catch( Throwable e ) {
            log.debug "Unexpected error while aborting execution", e
        }
    }

    private void operatorsForceTermination() {
        def operators = allOperators.toArray() as DataflowProcessor[]
        for( int i=0; i<operators.size(); i++ ) {
            operators[i].terminate()
        }
    }

    protected void forceTermination() {
        terminated = true
        processesBarrier.forceTermination()
        monitorsBarrier.forceTermination()
        allOperators *. terminate()

        execService?.shutdownNow()
        GParsConfig.shutdown()
    }

    boolean isTerminated() { terminated }

    boolean isAborted() { aborted }

    boolean isCancelled() { cancelled }

    boolean isSuccess() { !aborted && !cancelled && !failOnIgnore }

    boolean canSubmitTasks() {
        // tasks should be submitted even when 'failOnIgnore' is set to true
        // https://github.com/nextflow-io/nextflow/issues/5291
        return !aborted && !cancelled
    }

    void processRegister(TaskProcessor process) {
        log.trace ">>> barrier register (process: ${process.name})"
        processesBarrier.register(process)
    }

    void processDeregister(TaskProcessor process) {
        log.trace "<<< barrier arrive (process: ${process.name})"
        processesBarrier.arrive(process)
    }

    DAG getDag() { this.dag }

    ExecutorService getExecService() { execService }

    /**
     * Check preconditions before run the main script
     */
    protected void validate() {
        checkVersion()
    }

    @PackageScope void checkConfig() {
        final enabled = config.navigate('nextflow.enable.configProcessNamesValidation', true) as boolean
        if( enabled ) {
            final names = ScriptMeta.allProcessNames()
            final ver = "dsl${NF.dsl1 ?'1' :'2'}"
            log.debug "Workflow process names [$ver]: ${names.join(', ')}"
            validateConfig(names)
        }
        else {
            log.debug "Config process names validation disabled as requested"
        }
    }

    boolean enableModuleBinaries() {
        NF.isModuleBinariesEnabled()
    }

    boolean failOnIgnore() {
        config.navigate('workflow.failOnIgnore', false) as boolean
    }

    @PackageScope VersionNumber getCurrentVersion() {
        new VersionNumber(BuildInfo.version)
    }

    @PackageScope void checkVersion() {
        def version = manifest.getNextflowVersion()?.trim()
        if( !version )
            return

        // when the version string is prefix with a `!`
        // an exception is thrown is the version does not match
        boolean important = false
        if( version.startsWith('!') ) {
            important = true
            version = version.substring(1).trim()
        }

        if( !getCurrentVersion().matches(version) ) {
            important ? showVersionError(version) : showVersionWarning(version)
        }
    }

    @PackageScope void showVersionError(String ver) {
        throw new AbortOperationException("Nextflow version $BuildInfo.version does not match workflow required version: $ver")
    }

    @PackageScope void showVersionWarning(String ver) {
        log.warn "Nextflow version $BuildInfo.version does not match workflow required version: $ver -- Execution will continue, but things may break!"
    }

    /**
     * Validate the config file
     *
     * @param processNames The list of process names defined in the pipeline script
     */
    void validateConfig(Collection<String> processNames) {
        def warns = validateConfig0(processNames)
        for( String str : warns )
            log.warn str
    }

    protected List<String> validateConfig0(Collection<String> processNames) {
        List<String> result = []

        if( !(config.process instanceof Map) )
            return result

        // verifies that all process config names have a match with a defined process
        def keys = (config.process as Map).keySet()
        for(String key : keys) {
            String name = null
            if( key.startsWith('withName:') ) {
                name = key.substring('withName:'.length())
            }
            else if( key.startsWith('$') ) {
                name = key.substring(1)
                log.warn1 "Process config \$${name} is deprecated, use withName:'${name}' instead"
            }
            if( name )
                checkValidProcessName(processNames, name, result)
        }

        return result
    }

    /**
     * Check that the specified name belongs to the list of existing process names
     *
     * @param selector The process name to check
     * @param processNames The list of processes declared in the workflow script
     * @param errorMessage A list of strings used to return the error message to the caller
     * @return {@code true} if the name specified belongs to the list of process names or {@code false} otherwise
     */
    protected boolean checkValidProcessName(Collection<String> processNames, String selector, List<String> errorMessage)  {
        final matches = processNames.any { name -> ProcessConfig.matchesSelector(name, selector) }
        if( matches )
            return true

        def suggestion = processNames.closest(selector)
        def message = "There's no process matching config selector: $selector"
        if( suggestion )
            message += " -- Did you mean: ${suggestion.first()}?"
        errorMessage << message.toString()
        return false
    }

    /**
     * Register a shutdown hook to close services when the session terminates
     * @param Closure
     */
    void onShutdown( Runnable hook ) {
        if( !hook ) {
            log.warn "Shutdown hook cannot be null\n${ExceptionUtils.getStackTrace(new Exception())}"
            return
        }
        if( shutdownInitiated )
            throw new IllegalStateException("Session shutdown already initiated — Hook cannot be added: $hook")
        shutdownCallbacks.add(hook)
    }

    void notifyProcessCreate(TaskProcessor process) {
        notifyEvent(observersV1, ob -> ob.onProcessCreate(process))
        notifyEvent(observersV2, ob -> ob.onProcessCreate(process))
    }

    void notifyProcessTerminate(TaskProcessor process) {
        notifyEvent(observersV1, ob -> ob.onProcessTerminate(process))
        notifyEvent(observersV2, ob -> ob.onProcessTerminate(process))
    }

    void notifyTaskPending( TaskHandler handler ) {
        final trace = handler.getTraceRecord()
        notifyEvent(observersV1, ob -> ob.onProcessPending(handler, trace))
        notifyEvent(observersV2, ob -> ob.onTaskPending(new TaskEvent(handler, trace)))
    }

    /**
     * Notifies that a task has been submitted
     */
    void notifyTaskSubmit( TaskHandler handler ) {
        final task = handler.task
        log.info "[${task.hashLog}] ${task.runType.message} > ${task.name}"
        // -- save a record in the cache index
        cache.putIndexAsync(handler)

        final trace = handler.getTraceRecord()
        notifyEvent(observersV1, ob -> ob.onProcessSubmit(handler, trace))
        notifyEvent(observersV2, ob -> ob.onTaskSubmit(new TaskEvent(handler, trace)))
    }

    /**
     * Notifies task start event
     */
    void notifyTaskStart( TaskHandler handler ) {
        final trace = handler.getTraceRecord()
        notifyEvent(observersV1, ob -> ob.onProcessStart(handler, trace))
        notifyEvent(observersV2, ob -> ob.onTaskStart(new TaskEvent(handler, trace)))
    }

    /**
     * Notifies task termination event
     *
     * @param handler
     */
    void notifyTaskComplete( TaskHandler handler ) {
        // save the completed task in the cache DB
        final trace = handler.safeTraceRecord()
        cache.putTaskAsync(handler, trace)

        // set the pipeline to return non-exit code if specified
        if( handler.task.errorAction == ErrorStrategy.IGNORE && failOnIgnore() ) {
            log.debug "Setting fail-on-ignore flag due to ignored task '${handler.task.lazyName()}'"
            failOnIgnore = true
        }

        notifyEvent(observersV1, ob -> ob.onProcessComplete(handler, trace))
        notifyEvent(observersV2, ob -> ob.onTaskComplete(new TaskEvent(handler, trace)))
    }

    void notifyTaskCached( TaskHandler handler ) {
        final trace = handler.getTraceRecord()
        // save a record in the cache index only the when the trace record is available
        // otherwise it means that the event is trigger by a `stored dir` driven task
        if( trace ) {
            cache.cacheTaskAsync(handler)
        }

        notifyEvent(observersV1, ob -> ob.onProcessCached(handler, trace))
        notifyEvent(observersV2, ob -> ob.onTaskCached(new TaskEvent(handler, trace)))
    }

    void notifyBeforeWorkflowExecution() {
        validate()
    }

    void notifyAfterWorkflowExecution() {

    }

    void notifyFlowBegin() {
        notifyEvent(observersV1, ob -> ob.onFlowBegin())
        notifyEvent(observersV2, ob -> ob.onFlowBegin())
    }

    void notifyFlowCreate() {
        notifyEvent(observersV1, ob -> ob.onFlowCreate(this))
        notifyEvent(observersV2, ob -> ob.onFlowCreate(this))
    }

    void notifyWorkflowOutput(WorkflowOutputEvent event) {
        notifyEvent(observersV2, ob -> ob.onWorkflowOutput(event))
    }

    void notifyFilePublish(FilePublishEvent event) {
        notifyEvent(observersV1, ob -> ob.onFilePublish(event.target, event.source))
        notifyEvent(observersV2, ob -> ob.onFilePublish(event))
    }

    void notifyFlowComplete() {
        notifyEvent(observersV1, ob -> ob.onFlowComplete())
        notifyEvent(observersV2, ob -> ob.onFlowComplete())
    }

    /**
     * Notify a task failure
     *
     * @param handler
     * @param e
     */
    void notifyError( TaskHandler handler ) {

        final trace = handler?.safeTraceRecord()
        notifyEvent(observersV1, ob -> ob.onFlowError(handler, trace))
        notifyEvent(observersV2, ob -> ob.onFlowError(new TaskEvent(handler, trace)))

        if( !errorAction )
            return

        try {
            errorAction.call(trace)
        }
        catch( Throwable e ) {
            log.debug(e.getMessage(), e)
        }
    }

    private static <T> void notifyEvent(List<T> observers, Consumer<T> action) {
        for ( int i=0; i<observers.size(); i++) {
            final observer = observers.get(i)
            try {
                action.accept(observer)
            }
            catch ( Throwable e ) {
                log.debug(e.getMessage(), e)
            }
        }
    }

    /**
     * Define the error event handler
     * @param action
     */
    void onError( Closure action ) {
        errorAction = action
    }

    /**
     * Delete the workflow work directory from tasks temporary files
     */
    void cleanup() {
        if( !workDir || !config.cleanup )
            return

        if( aborted || cancelled || error )
            return

        if( workDir.scheme != 'file' ) {
            log.warn "The `cleanup` option is not supported for remote work directory: ${workDir.toUriString()}"
            return
        }

        log.trace "Cleaning-up workdir"
        try (CacheDB db = CacheFactory.create(uniqueId, runName).openForRead()) {
            db.eachRecord { HashCode hash, TraceRecord record ->
                def deleted = db.removeTaskEntry(hash)
                if( deleted ) {
                    // delete folder
                    FileHelper.deletePath(FileHelper.asPath(record.workDir))
                }
            }
            log.trace "Clean workdir complete"
        }
        catch( Exception e ) {
            log.warn("Failed to cleanup work dir: ${workDir.toUriString()}", e)
        }
    }

    @Memoized
    CondaConfig getCondaConfig() {
        final cfg = config.conda as Map ?: Collections.emptyMap()
        return new CondaConfig(cfg, getSystemEnv())
    }

    @Memoized
    SpackConfig getSpackConfig() {
        final cfg = config.spack as Map ?: Collections.emptyMap()
        return new SpackConfig(cfg, getSystemEnv())
    }

    /**
     * Get the container engine configuration for the specified engine. If no engine is specified
     * if returns the one enabled in the configuration file. If no configuration is found
     * defaults to {@code docker} engine.
     *
     * @param engine
     *      The container engine name for which
     * @return
     *      A {@link ContainerConfig} object representing the container engine configuration defined in config object
     */
    @Memoized
    ContainerConfig getContainerConfig(String engine) {

        final allEngines = new LinkedList<Map>()
        getContainerConfig0('docker', allEngines)
        getContainerConfig0('podman', allEngines)
        getContainerConfig0('sarus', allEngines)
        getContainerConfig0('shifter', allEngines)
        getContainerConfig0('udocker', allEngines)
        getContainerConfig0('singularity', allEngines)
        getContainerConfig0('apptainer', allEngines)
        getContainerConfig0('charliecloud', allEngines)

        if( engine ) {
            final result = allEngines.find(it -> it.engine==engine) ?: [engine: engine]
            return new ContainerConfig(result)
        }

        final enabled = allEngines.findAll(it -> it.enabled?.toString() == 'true')
        if( enabled.size() > 1 ) {
            final names = enabled.collect(it -> it.engine)
            throw new IllegalConfigException("Cannot enable more than one container engine -- Choose either one of: ${names.join(', ')}")
        }
        if( enabled ) {
            return new ContainerConfig(enabled.get(0))
        }
        if( allEngines ) {
            return new ContainerConfig(allEngines.get(0))
        }
        return new ContainerConfig(engine:'docker')
    }

    ContainerConfig getContainerConfig() {
        return getContainerConfig(null)
    }

    private void getContainerConfig0(String engine, List<Map> drivers) {
        assert engine
        final entry = this.config?.get(engine)
        if( entry instanceof Map ) {
            final config0 = new LinkedHashMap()
            config0.putAll((Map)entry)
            config0.put('engine', engine)
            drivers.add(config0)
        }
        else if( entry!=null ) {
            log.warn "Malformed configuration for container engine '$engine' -- One or more attributes should be provided"
        }
    }

    @Memoized
    def getExecConfigProp( String execName, String name, Object defValue, Map env = null  ) {
        def result = ConfigHelper.getConfigProperty(config.executor, execName, name )
        if( result != null )
            return result

        // -- try to fallback sys env
        def key = "NXF_EXECUTOR_${name.toUpperCase().replaceAll(/\./,'_')}".toString()
        if( env == null ) env = System.getenv()
        return env.containsKey(key) ? env.get(key) : defValue
    }

    @Memoized
    def getConfigAttribute(String name, defValue )  {
        def result = getMap0(getConfig(),name,name)
        if( result != null )
            return result

        def key = "NXF_${name.toUpperCase().replaceAll(/\./,'_')}".toString()
        def env = getSystemEnv()
        return (env.containsKey(key) ? env.get(key) : defValue)
    }

    private getMap0(Map map, String name, String fqn) {
        def p=name.indexOf('.')
        if( p == -1 )
            return map.get(name)
        else {
            def k=name.substring(0,p)
            def v=map.get(k)
            if( v == null )
                return null
            if( v instanceof Map )
                return getMap0(v,name.substring(p+1),fqn)
            throw new IllegalArgumentException("Not a valid config attribute: $fqn -- Missing element: $k")
        }
    }

    @Memoized
    protected Map<String,String> getSystemEnv() {
        new HashMap<String, String>(System.getenv())
    }

    @CompileDynamic
    def fetchContainers() {

        def result = [:]
        if( config.process instanceof Map<String,?> ) {

            /*
             * look for `container` definition at process level
             */
            config.process.each { String name, value ->
                if( name.startsWith('withName:') ) {
                    name = name.substring('withName:'.length())
                }
                else if( name.startsWith('$') ) {
                    name = name.substring(1)
                    log.warn1 "Process config \$${name} is deprecated, use withName:'${name}' instead"
                }

                if( value instanceof Map && value.container ) {
                    result[name] = resolveClosure(value.container)
                }
            }

            /*
             * default container definition
             */
            def container = config.process.container
            if( container ) {
                if( result ) {
                    result['default'] = resolveClosure(container)
                }
                else {
                    result = resolveClosure(container)
                }
            }

        }

        return result
    }

    /**
     * Resolve dynamically defined attributes to the actual value
     *
     * @param val A process container definition either a plain string or a closure
     * @return The actual container value
     */
    protected String resolveClosure( val ) {
        if( val instanceof Closure ) {
            try {
                return val.cloneWith(binding).call()
            }
            catch( Exception e ) {
                log.debug "Unable to resolve dynamic `container` directive -- cause: ${e.message ?: e}"
                return "(dynamic resolved)"
            }
        }

        return String.valueOf(val)
    }


    /**
     * Defines the number of tasks the executor will handle in a parallel manner
     *
     * @param execName The executor name
     * @param defValue The default value if setting is not defined in the configuration file
     * @return The value of tasks to handle in parallel
     */
    @Memoized
    int getQueueSize( String execName, int defValue ) {
        getExecConfigProp(execName, 'queueSize', defValue) as int
    }

    /**
     * Determines how often a poll occurs to check for a process termination
     *
     * @param execName The executor name
     * @param defValue The default value if setting is not defined in the configuration file
     * @return A {@code Duration} object. Default '1 second'
     */
    @Memoized
    Duration getPollInterval( String execName, Duration defValue = Duration.of('1sec') ) {
        getExecConfigProp( execName, 'pollInterval', defValue ) as Duration
    }

    /**
     *  Determines how long the executors waits before return an error status when a process is
     *  terminated but the exit file does not exist or it is empty. This setting is used only by grid executors
     *
     * @param execName The executor name
     * @param defValue The default value if setting is not defined in the configuration file
     * @return A {@code Duration} object. Default '90 second'
     */
    @Memoized
    Duration getExitReadTimeout( String execName, Duration defValue = Duration.of('90sec') ) {
        getExecConfigProp( execName, 'exitReadTimeout', defValue ) as Duration
    }

    /**
     * Determines how often the executor status is written in the application log file
     *
     * @param execName The executor name
     * @param defValue The default value if setting is not defined in the configuration file
     * @return A {@code Duration} object. Default '5 minutes'
     */
    @Memoized
    Duration getMonitorDumpInterval( String execName, Duration defValue = Duration.of('5min')) {
        getExecConfigProp(execName, 'dumpInterval', defValue) as Duration
    }

    /**
     * Determines how often the queue status is fetched from the cluster system. This setting is used only by grid executors
     *
     * @param execName The executor name
     * @param defValue  The default value if setting is not defined in the configuration file
     * @return A {@code Duration} object. Default '1 minute'
     */
    @Memoized
    Duration getQueueStatInterval( String execName, Duration defValue = Duration.of('1min') ) {
        getExecConfigProp(execName, 'queueStatInterval', defValue) as Duration
    }

    void printConsole(String str, boolean newLine=false) {
        if( ansiLogObserver )
            ansiLogObserver.appendInfo(str)
        else if( newLine )
            System.out.println(str)
        else
            System.out.print(str)
    }

    void printConsole(Path file) {
        ansiLogObserver ? ansiLogObserver.appendInfo(file.text) : Files.copy(file, System.out)
    }

    private volatile ThreadPoolManager finalizePoolManager

    @Memoized
    synchronized ExecutorService taskFinalizerExecutorService() {
        finalizePoolManager = new ThreadPoolManager('TaskFinalizer')
        return finalizePoolManager
                .withConfig(config)
                .withShutdownMessage(
                    "Waiting for remaining tasks to complete (%d tasks)",
                    "Exiting before some tasks were completed"
                )
                .create()
    }

    private volatile ThreadPoolManager publishPoolManager

    @Memoized
    synchronized ExecutorService publishDirExecutorService() {
        publishPoolManager = new ThreadPoolManager('PublishDir')
        return publishPoolManager
                .withConfig(config)
                .create()
    }

}
