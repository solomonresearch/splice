// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.ValidatorLivenessActivityRecord
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.ClosedMiningRound
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.environment.DarResources
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.plugins.TokenStandardCliSanityCheckPlugin
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.{
  AdvanceOpenMiningRoundTrigger,
  ExpireRewardCouponsTrigger,
}
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingConfig.InitialPackageConfig
import org.lfdecentralizedtrust.splice.util.TriggerTestUtil
import org.lfdecentralizedtrust.splice.wallet.automation.CollectRewardsAndMergeAmuletsTrigger
import org.scalatest.time.{Minute, Span}
import scala.jdk.CollectionConverters.*

@org.lfdecentralizedtrust.splice.util.scalatesttags.NoDamlCompatibilityCheck
class RewardExpiryIntegrationTest extends IntegrationTest with TriggerTestUtil {

  // this test starts up on older version (see initialPackageConfig), which don't define token-standard interfaces
  // and thus everything will show up as raw create/archives.
  override protected lazy val tokenStandardCliBehavior
      : TokenStandardCliSanityCheckPlugin.OutputCreateArchiveBehavior =
    TokenStandardCliSanityCheckPlugin.OutputCreateArchiveBehavior.IgnoreAll

  override lazy val sanityChecksIgnoredRootExercises = Seq(
    (AmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID, "Archive")
  )

  override lazy val sanityChecksIgnoredRootCreates = Seq(
    AmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID
  )

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(1, Minute)))

  private val initialPackageConfig = InitialPackageConfig(
    amuletVersion = "0.1.9",
    amuletNameServiceVersion = "0.1.9",
    dsoGovernanceVersion = "0.1.13",
    validatorLifecycleVersion = "0.1.3",
    walletVersion = "0.1.9",
    walletPaymentsVersion = "0.1.9",
  )

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withNoVettedPackages(implicit env => env.validators.local.map(_.participantClient))
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
          _.copy(
            initialPackageConfig = initialPackageConfig,
            initialTickDuration = NonNegativeFiniteDuration.ofMillis(500),
          )
        )(config)
      )
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAutomationConfig(ConfigTransforms.ConfigurableApp.Sv)(
          _.withPausedTrigger[AdvanceOpenMiningRoundTrigger]
            .withPausedTrigger[ExpireRewardCouponsTrigger]
        )(config)
      )
      .addConfigTransform((_, conf) =>
        ConfigTransforms.updateAutomationConfig(ConfigTransforms.ConfigurableApp.Validator)(
          _.withPausedTrigger[CollectRewardsAndMergeAmuletsTrigger]
        )(conf)
      )
      .addConfigTransform((_, config) =>
        ConfigTransforms.useDecentralizedSynchronizerSplitwell()(config)
      )

  "reward expiry is not broken" in { implicit env =>
    val activityRecord = eventually() {
      aliceValidatorBackend.participantClient.ledger_api_extensions.acs
        .filterJava(ValidatorLivenessActivityRecord.COMPANION)(
          aliceValidatorBackend.getValidatorPartyId(),
          _.data.round.number == 0,
        )
        .loneElement
    }
    actAndCheck("Advance by one tick", advanceRoundsByOneTickViaAutomation())(
      "Round 0 is closed",
      _ =>
        sv1Backend.participantClient.ledger_api_extensions.acs
          .filterJava(ClosedMiningRound.COMPANION)(
            dsoParty
          )
          .loneElement
          .data
          .round
          .number shouldBe 0,
    )
    actAndCheck(
      "SV1 uploads the latest dso governance",
      sv1Backend.participantClient.dars.upload(
        s"daml/dars/splice-dso-governance-${DarResources.dsoGovernance.bootstrap.metadata.version}.dar"
      ),
    )(
      "SV1 has vetted the latest dso governance",
      _ =>
        sv1Backend.participantClient.topology.vetted_packages
          .list(
            Some(TopologyStoreId.Synchronizer(decentralizedSynchronizerId)),
            filterParticipant = sv1Backend.participantClient.id.filterString,
          )
          .loneElement
          .item
          .packages
          .map(_.packageId) should contain(DarResources.dsoGovernance.bootstrap.packageId),
    )
    // Recreate AmuletRules in new package id.
    actAndCheck(
      "Recreate amulet rules in new package id", {
        val amuletRules = sv1ScanBackend.getAmuletRules()
        amuletRules.contract.identifier.getPackageId shouldBe DarResources.amulet_0_1_9.packageId
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
          Seq(dsoParty),
          amuletRules.contract.contractId.exerciseArchive().commands.asScala.toSeq ++
            amuletRules.contract.payload.create().commands.asScala.toSeq,
        )
      },
    )(
      "Amulet rules is created in new package id",
      _ => {
        sv1ScanBackend
          .getAmuletRules()
          .contract
          .identifier
          .getPackageId shouldBe DarResources.amulet.bootstrap.packageId
      },
    )
    sv1Backend.dsoDelegateBasedAutomation
      .trigger[ExpireRewardCouponsTrigger]
      .resume()
    eventually() {
      aliceValidatorBackend.participantClient.ledger_api_extensions.acs
        .filterJava(ValidatorLivenessActivityRecord.COMPANION)(
          aliceValidatorBackend.getValidatorPartyId(),
          _.data.round.number == 0,
        ) shouldBe empty
    }
    println(activityRecord)
  }
}
