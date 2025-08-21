package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.WalletTxLogTestUtil
import org.lfdecentralizedtrust.splice.util.WalletTestUtil
import com.digitalasset.canton.HasExecutionContext


class ManualSignatureIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with WalletTxLogTestUtil {

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withManualStart
      .withSequencerConnectionsFromScanDisabled()
  }

  "synchronizer" should {

    "rotate signatures" in { implicit env =>
      sv1Backend.startSync()
    }
  }
}
