package com.bejo;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Enforces the modular-monolith boundaries so they can't quietly rot. The codebase is organised into
 * feature modules under {@code com.bejo.<module>}:
 *
 * <ul>
 *   <li><b>shared</b> — cross-cutting library code (util, exception, persistence converters, the API
 *       envelope). Depends on no module; everyone may depend on it.</li>
 *   <li><b>notification</b> — push delivery infrastructure. Pure (Option B): it owns the
 *       {@code PushRecipientDirectory}/{@code StaleTokenHandler} ports that core implements, so it
 *       depends on no other module and is the only place that talks to FCM.</li>
 *   <li><b>core</b> — users, business, auth, presence, QRIS, sessions, settings. The foundation.</li>
 *   <li><b>commerce</b> — transactions, products, stock, the payment callback.</li>
 *   <li><b>campaign</b> — voice-ad campaigns.</li>
 *   <li><b>config</b> — the Spring bootstrap / composition root; may wire everything, and nothing
 *       may depend on it.</li>
 * </ul>
 *
 * The allowed dependency direction is {@code commerce, campaign → core → notification, shared}.
 */
@AnalyzeClasses(packages = "com.bejo", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule modules_are_free_of_cycles =
            slices().matching("com.bejo.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule notification_depends_on_no_other_module =
            noClasses().that().resideInAPackage("com.bejo.notification..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.bejo.core..", "com.bejo.commerce..", "com.bejo.campaign..",
                            "com.bejo.shared..", "com.bejo.config..")
                    .because("notification is pure delivery infrastructure — it owns ports that core implements (Option B)");

    @ArchTest
    static final ArchRule shared_depends_on_no_other_module =
            noClasses().that().resideInAPackage("com.bejo.shared..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.bejo.core..", "com.bejo.commerce..", "com.bejo.campaign..",
                            "com.bejo.notification..", "com.bejo.config..")
                    .because("shared is cross-cutting code everyone may use, but which depends on no module");

    @ArchTest
    static final ArchRule core_does_not_depend_on_feature_modules =
            noClasses().that().resideInAPackage("com.bejo.core..")
                    .should().dependOnClassesThat().resideInAnyPackage("com.bejo.commerce..", "com.bejo.campaign..")
                    .because("core is the foundation; feature modules depend on it, not the other way round");

    @ArchTest
    static final ArchRule commerce_does_not_depend_on_campaign =
            noClasses().that().resideInAPackage("com.bejo.commerce..")
                    .should().dependOnClassesThat().resideInAPackage("com.bejo.campaign..")
                    .because("sibling feature modules must not couple to each other");

    @ArchTest
    static final ArchRule campaign_does_not_depend_on_commerce =
            noClasses().that().resideInAPackage("com.bejo.campaign..")
                    .should().dependOnClassesThat().resideInAPackage("com.bejo.commerce..")
                    .because("sibling feature modules must not couple to each other");

    @ArchTest
    static final ArchRule modules_do_not_depend_on_the_bootstrap_layer =
            noClasses().that().resideInAnyPackage(
                            "com.bejo.core..", "com.bejo.commerce..", "com.bejo.campaign..",
                            "com.bejo.notification..", "com.bejo.shared..")
                    .should().dependOnClassesThat().resideInAPackage("com.bejo.config..")
                    .because("config is the composition root — modules must not depend on it");

    @ArchTest
    static final ArchRule only_notification_talks_to_fcm =
            noClasses().that().resideOutsideOfPackage("com.bejo.notification..")
                    .should().dependOnClassesThat().haveFullyQualifiedName("com.google.firebase.messaging.FirebaseMessaging")
                    .because("all FCM sending goes through the notification module (PushJobSender)");
}
