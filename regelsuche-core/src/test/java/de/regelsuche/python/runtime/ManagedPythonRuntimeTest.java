package de.regelsuche.python.runtime;

import org.junit.jupiter.api.Test;

class ManagedPythonRuntimeTest {
    @Test void warmSessionAndClose() throws Exception { ManagedPythonRuntimeChecks.warmSessionAndClose(); }
    @Test void unicodeAndInputLimits() throws Exception { ManagedPythonRuntimeChecks.unicodeAndInputLimits(); }
    @Test void outputLimitRetiresSession() throws Exception { ManagedPythonRuntimeChecks.outputLimitRetiresSession(); }
    @Test void executionFailureRetiresSession() throws Exception { ManagedPythonRuntimeChecks.executionFailureRetiresSession(); }
    @Test void initializationFailureRetiresSession() throws Exception { ManagedPythonRuntimeChecks.initializationFailureRetiresSession(); }
    @Test void blockedInvocationIsCancelled() throws Exception { ManagedPythonRuntimeChecks.blockedInvocationIsCancelled(); }
    @Test void cancelledStartupCannotReplaceNewGeneration() throws Exception { ManagedPythonRuntimeChecks.cancelledStartupCannotReplaceNewGeneration(); }
    @Test void queueTimeoutDoesNotCancelOwner() throws Exception { ManagedPythonRuntimeChecks.queueTimeoutDoesNotCancelOwner(); }
    @Test void interruptRestoresFlagAndRetiresOwner() throws Exception { ManagedPythonRuntimeChecks.interruptRestoresFlagAndRetiresOwner(); }
    @Test void concurrentCallsUseOneWorker() throws Exception { ManagedPythonRuntimeChecks.concurrentCallsUseOneWorker(); }
    @Test void cleanupCannotMaskExecutionFailure() throws Exception { ManagedPythonRuntimeChecks.cleanupCannotMaskExecutionFailure(); }
    @Test void invalidConfigurationAndDeadline() throws Exception { ManagedPythonRuntimeChecks.invalidConfigurationAndDeadline(); }
}
