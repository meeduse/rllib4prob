package fr.polytech.mnia.exploration.prob;

import java.nio.file.Path;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Stage;

import de.prob.MainModule;
import de.prob.scripting.Api;
import de.prob.statespace.State;
import de.prob.statespace.StateSpace;
import de.prob.statespace.Transition;

/** One isolated B-machine copy, one ProB API instance, and one ProB StateSpace. */
public final class ProBWorkerSession implements AutoCloseable {
    private final ModelWorkspace workspace;
    private final StateSpace stateSpace;
    private boolean closed;

    private ProBWorkerSession(ModelWorkspace workspace, StateSpace stateSpace) {
        this.workspace = workspace;
        this.stateSpace = stateSpace;
    }

    /** Loads the local copy, never the shared original B file. */
    public static ProBWorkerSession open(Path sourceRoot, Path relativeMachine, boolean preserveWorkspace)
            throws Exception {
        ModelWorkspace workspace = ModelWorkspace.create(sourceRoot, relativeMachine, preserveWorkspace);
        try {
            Injector injector = Guice.createInjector(Stage.PRODUCTION, new MainModule());
            Api api = injector.getInstance(Api.class);
            StateSpace stateSpace = api.b_load(workspace.machinePath().toAbsolutePath().toString());
            return new ProBWorkerSession(workspace, stateSpace);
        } catch (Exception | Error error) {
            try {
                workspace.close();
            } catch (Exception cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
    }

    public Path workspaceDirectory() {
        return workspace.directory();
    }

    public Path localMachinePath() {
        return workspace.machinePath();
    }

    public StateSpace stateSpace() {
        ensureOpen();
        return stateSpace;
    }

    /** Skips ProB's administrative setup/initialisation transitions. */
    public State initialState() {
        ensureOpen();
        State initial = stateSpace.getRoot();
        Transition setup = initial.findTransition(Transition.SETUP_CONSTANTS_NAME);
        if (setup != null) {
            initial = setup.getDestination();
        }
        Transition init = initial.findTransition(Transition.INITIALISE_MACHINE_NAME);
        if (init != null) {
            initial = init.getDestination();
        }
        return initial.exploreIfNeeded();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("ProB session already closed");
        }
    }

    /** Closes the ProB kernel before removing this session's private files. */
    @Override
    public synchronized void close() throws Exception {
        if (closed) {
            return;
        }
        closed = true;
        try {
            stateSpace.kill();
        } finally {
            workspace.close();
        }
    }
}
