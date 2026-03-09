package org.cibseven.community.otel.integration;

import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.delegate.JavaDelegate;

/**
 * Trivial no-op delegate used by the integration test BPMN process.
 */
public class TestDelegate implements JavaDelegate {

  @Override
  public void execute(DelegateExecution execution) {
    // No-op: the test only needs the process to complete successfully.
  }
}
