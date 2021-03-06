package org.jboss.as.clustering.infinispan.subsystem;

import org.jboss.as.controller.AbstractAddStepHandler;
import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.OperationDefinition;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.SimpleAttributeDefinition;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.SimpleOperationDefinitionBuilder;
import org.jboss.as.controller.SimpleResourceDefinition;
import org.jboss.as.controller.descriptions.ResourceDescriptionResolver;
import org.jboss.as.controller.operations.validation.EnumValidator;
import org.jboss.as.controller.registry.AttributeAccess;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;

/**
 * Base class for cache resources which require common cache attributes only.
 *
 * @author Richard Achmatowicz (c) 2011 Red Hat Inc.
 */
public class CacheResource extends SimpleResourceDefinition {

    // attributes
    static final SimpleAttributeDefinition BATCHING =
            new SimpleAttributeDefinitionBuilder(ModelKeys.BATCHING, ModelType.BOOLEAN, true)
                    .setXmlName(Attribute.BATCHING.getLocalName())
                    .setAllowExpression(false)
                    .setFlags(AttributeAccess.Flag.RESTART_ALL_SERVICES)
                    .setDefaultValue(new ModelNode().set(false))
                    .build();

    static final SimpleAttributeDefinition CACHE_MODULE =
            new SimpleAttributeDefinitionBuilder(ModelKeys.MODULE, ModelType.STRING, true)
                    .setXmlName(Attribute.MODULE.getLocalName())
                    .setAllowExpression(false)
                    .setFlags(AttributeAccess.Flag.RESTART_ALL_SERVICES)
                    .setValidator(new ModuleIdentifierValidator(true))
                    .build();

    static final SimpleAttributeDefinition INDEXING =
            new SimpleAttributeDefinitionBuilder(ModelKeys.INDEXING, ModelType.STRING, true)
                    .setXmlName(Attribute.INDEX.getLocalName())
                    .setAllowExpression(false)
                    .setFlags(AttributeAccess.Flag.RESTART_ALL_SERVICES)
                    .setValidator(new EnumValidator<Indexing>(Indexing.class, true, false))
                    .setDefaultValue(new ModelNode().set(Indexing.NONE.name()))
                    .build();

    static final SimpleAttributeDefinition JNDI_NAME =
            new SimpleAttributeDefinitionBuilder(ModelKeys.JNDI_NAME, ModelType.STRING, true)
                    .setXmlName(Attribute.JNDI_NAME.getLocalName())
                    .setAllowExpression(false)
                    .setFlags(AttributeAccess.Flag.RESTART_ALL_SERVICES)
                    .build();

    static final SimpleAttributeDefinition START =
            new SimpleAttributeDefinitionBuilder(ModelKeys.START, ModelType.STRING, true)
                    .setXmlName(Attribute.START.getLocalName())
                    .setAllowExpression(false)
                    .setFlags(AttributeAccess.Flag.RESTART_ALL_SERVICES)
                    .setValidator(new EnumValidator<StartMode>(StartMode.class, true, false))
                    .setDefaultValue(new ModelNode().set(StartMode.LAZY.name()))
                    .build();

    static final AttributeDefinition[] CACHE_ATTRIBUTES = {BATCHING, CACHE_MODULE, INDEXING, JNDI_NAME, START};

    // here for legacy purposes only
    static final SimpleAttributeDefinition NAME =
            new SimpleAttributeDefinitionBuilder(ModelKeys.NAME, ModelType.STRING, true)
                    .setXmlName(Attribute.NAME.getLocalName())
                    .setAllowExpression(false)
                    .setFlags(AttributeAccess.Flag.RESTART_ALL_SERVICES)
                    .build();

    // operations
    static final OperationDefinition CLEAR_CACHE =
            new SimpleOperationDefinitionBuilder("clear-cache",
                    InfinispanExtension.getResourceDescriptionResolver("cache")
            ).build();
    static final OperationDefinition RESET_STATISTICS =
            new SimpleOperationDefinitionBuilder("reset-statistics",
                    InfinispanExtension.getResourceDescriptionResolver("cache")
            ).build();
    static final OperationDefinition RESET_ACTIVATION_STATISTICS =
            new SimpleOperationDefinitionBuilder(
                    "reset-activation-statistics",
                    InfinispanExtension.getResourceDescriptionResolver("cache")
            ).build();
    static final OperationDefinition RESET_INVALIDATION_STATISTICS =
            new SimpleOperationDefinitionBuilder(
                    "reset-invalidation-statistics",
                    InfinispanExtension.getResourceDescriptionResolver("cache")
            ).build();
    static final OperationDefinition RESET_PASSIVATION_STATISTICS =
            new SimpleOperationDefinitionBuilder(
                    "reset-passivation-statistics",
                    InfinispanExtension.getResourceDescriptionResolver("cache")
            ).build();

    private final boolean runtimeRegistration ;

    public CacheResource(PathElement pathElement, ResourceDescriptionResolver descriptionResolver, AbstractAddStepHandler addHandler, OperationStepHandler removeHandler, boolean runtimeRegistration) {
        super(pathElement, descriptionResolver, addHandler, removeHandler);
        this.runtimeRegistration = runtimeRegistration ;
    }

    @Override
    public void registerAttributes(ManagementResourceRegistration resourceRegistration) {
        super.registerAttributes(resourceRegistration);

        // do we really need a special handler here?
        final OperationStepHandler writeHandler = new CacheWriteAttributeHandler(CACHE_ATTRIBUTES);
        for (AttributeDefinition attr : CACHE_ATTRIBUTES) {
            resourceRegistration.registerReadWriteAttribute(attr, CacheReadAttributeHandler.INSTANCE, writeHandler);
        }

        // register runtime cache read-only metrics (attributes and handlers)
        if(isRuntimeRegistration()) {
            CacheMetricsHandler.INSTANCE.registerCommonMetrics(resourceRegistration);
        }
    }

    @Override
    public void registerOperations(ManagementResourceRegistration resourceRegistration) {
        super.registerOperations(resourceRegistration);
        if (runtimeRegistration) {
            resourceRegistration.registerOperationHandler(CacheResource.CLEAR_CACHE.getName(), CacheCommands.ClearCacheCommand.INSTANCE, CacheResource.CLEAR_CACHE.getDescriptionProvider());
            resourceRegistration.registerOperationHandler(CacheResource.RESET_STATISTICS.getName(), CacheCommands.ResetCacheStatisticsCommand.INSTANCE, CacheResource.RESET_STATISTICS.getDescriptionProvider());
            resourceRegistration.registerOperationHandler(CacheResource.RESET_ACTIVATION_STATISTICS.getName(), CacheCommands.ResetActivationStatisticsCommand.INSTANCE, CacheResource.RESET_ACTIVATION_STATISTICS.getDescriptionProvider());
            resourceRegistration.registerOperationHandler(CacheResource.RESET_INVALIDATION_STATISTICS.getName(), CacheCommands.ResetPassivationStatisticsCommand.INSTANCE, CacheResource.RESET_INVALIDATION_STATISTICS.getDescriptionProvider());
            resourceRegistration.registerOperationHandler(CacheResource.RESET_PASSIVATION_STATISTICS.getName(), CacheCommands.ResetPassivationStatisticsCommand.INSTANCE, CacheResource.RESET_PASSIVATION_STATISTICS.getDescriptionProvider());
        }
    }

    @Override
    public void registerChildren(ManagementResourceRegistration resourceRegistration) {
        super.registerChildren(resourceRegistration);

        resourceRegistration.registerSubModel(new LockingResource());
        resourceRegistration.registerSubModel(new TransactionResource(runtimeRegistration));
        resourceRegistration.registerSubModel(new EvictionResource());
        resourceRegistration.registerSubModel(new ExpirationResource());
        resourceRegistration.registerSubModel(new LoaderResource());
        resourceRegistration.registerSubModel(new ClusterLoaderResource());
        resourceRegistration.registerSubModel(new StoreResource());
        resourceRegistration.registerSubModel(new FileStoreResource());
        resourceRegistration.registerSubModel(new StringKeyedJDBCStoreResource());
        resourceRegistration.registerSubModel(new BinaryKeyedJDBCStoreResource());
        resourceRegistration.registerSubModel(new MixedKeyedJDBCStoreResource());
        resourceRegistration.registerSubModel(new RemoteStoreResource());
    }

    public boolean isRuntimeRegistration() {
        return runtimeRegistration;
    }
}
