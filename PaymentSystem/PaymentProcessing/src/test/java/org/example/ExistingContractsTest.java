package org.example;

import org.junit.jupiter.api.Test;
import org.killbill.billing.payment.core.PaymentCoreApiTest;

class ExistingContractsTest {
    @Test
    void coreApi() throws Exception {
        PaymentCoreApiTest.main(new String[0]);
    }

    @Test
    void workflowLifecycle() throws Exception {
        PaymentWorkflowRunnerTest.main(new String[0]);
    }

    @Test
    void operationCallback() throws Exception {
        OperationCallbackBaseTest.main(new String[0]);
    }

    @Test
    void persistenceWithH2() throws Exception {
        PaymentWorkflowDAOHelperTest.main(new String[0]);
    }
}
