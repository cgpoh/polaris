/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.polaris.service.quarkus.catalog;

import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableMap;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.SecurityContext;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.view.ViewCatalogTests;
import org.apache.polaris.core.PolarisCallContext;
import org.apache.polaris.core.PolarisConfiguration;
import org.apache.polaris.core.PolarisConfigurationStore;
import org.apache.polaris.core.PolarisDiagnostics;
import org.apache.polaris.core.admin.model.FileStorageConfigInfo;
import org.apache.polaris.core.admin.model.StorageConfigInfo;
import org.apache.polaris.core.auth.AuthenticatedPolarisPrincipal;
import org.apache.polaris.core.auth.PolarisAuthorizerImpl;
import org.apache.polaris.core.context.CallContext;
import org.apache.polaris.core.context.RealmContext;
import org.apache.polaris.core.entity.CatalogEntity;
import org.apache.polaris.core.entity.PolarisEntity;
import org.apache.polaris.core.entity.PolarisEntitySubType;
import org.apache.polaris.core.entity.PolarisEntityType;
import org.apache.polaris.core.entity.PrincipalEntity;
import org.apache.polaris.core.persistence.MetaStoreManagerFactory;
import org.apache.polaris.core.persistence.PolarisEntityManager;
import org.apache.polaris.core.persistence.PolarisMetaStoreManager;
import org.apache.polaris.core.persistence.cache.EntityCache;
import org.apache.polaris.core.storage.cache.StorageCredentialCache;
import org.apache.polaris.service.admin.PolarisAdminService;
import org.apache.polaris.service.catalog.BasePolarisCatalog;
import org.apache.polaris.service.catalog.io.DefaultFileIOFactory;
import org.apache.polaris.service.storage.PolarisStorageIntegrationProviderImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

@QuarkusTest
public class BasePolarisCatalogViewTest extends ViewCatalogTests<BasePolarisCatalog> {
  public static final String CATALOG_NAME = "polaris-catalog";

  @Inject MetaStoreManagerFactory managerFactory;
  @Inject PolarisConfigurationStore configurationStore;
  @Inject PolarisDiagnostics diagServices;

  private BasePolarisCatalog catalog;

  private String realmName;
  private PolarisMetaStoreManager metaStoreManager;
  private PolarisCallContext polarisContext;

  @BeforeAll
  public static void setUpMocks() {
    PolarisStorageIntegrationProviderImpl mock =
        Mockito.mock(PolarisStorageIntegrationProviderImpl.class);
    QuarkusMock.installMockForType(mock, PolarisStorageIntegrationProviderImpl.class);
  }

  @BeforeEach
  public void setUpTempDir(@TempDir Path tempDir) throws Exception {
    // see https://github.com/quarkusio/quarkus/issues/13261
    Field field = ViewCatalogTests.class.getDeclaredField("tempDir");
    field.setAccessible(true);
    field.set(this, tempDir);
  }

  @BeforeEach
  public void before(TestInfo testInfo) {
    realmName =
        "realm_%s_%s"
            .formatted(
                testInfo.getTestMethod().map(Method::getName).orElse("test"), System.nanoTime());
    RealmContext realmContext = () -> realmName;

    metaStoreManager = managerFactory.getOrCreateMetaStoreManager(realmContext);
    polarisContext =
        new PolarisCallContext(
            managerFactory.getOrCreateSessionSupplier(realmContext).get(),
            diagServices,
            configurationStore,
            Clock.systemDefaultZone());

    PolarisEntityManager entityManager =
        new PolarisEntityManager(
            metaStoreManager, new StorageCredentialCache(), new EntityCache(metaStoreManager));

    CallContext callContext = CallContext.of(realmContext, polarisContext);
    CallContext.setCurrentContext(callContext);

    PrincipalEntity rootEntity =
        new PrincipalEntity(
            PolarisEntity.of(
                metaStoreManager
                    .readEntityByName(
                        polarisContext,
                        null,
                        PolarisEntityType.PRINCIPAL,
                        PolarisEntitySubType.NULL_SUBTYPE,
                        "root")
                    .getEntity()));
    AuthenticatedPolarisPrincipal authenticatedRoot =
        new AuthenticatedPolarisPrincipal(rootEntity, Set.of());

    SecurityContext securityContext = Mockito.mock(SecurityContext.class);
    when(securityContext.getUserPrincipal()).thenReturn(authenticatedRoot);
    when(securityContext.isUserInRole(Mockito.anyString())).thenReturn(true);
    PolarisAdminService adminService =
        new PolarisAdminService(
            callContext,
            entityManager,
            metaStoreManager,
            securityContext,
            new PolarisAuthorizerImpl(new PolarisConfigurationStore() {}));
    adminService.createCatalog(
        new CatalogEntity.Builder()
            .setName(CATALOG_NAME)
            .addProperty(PolarisConfiguration.ALLOW_EXTERNAL_TABLE_LOCATION.catalogConfig(), "true")
            .addProperty(
                PolarisConfiguration.ALLOW_UNSTRUCTURED_TABLE_LOCATION.catalogConfig(), "true")
            .setDefaultBaseLocation("file://tmp")
            .setStorageConfigurationInfo(
                new FileStorageConfigInfo(
                    StorageConfigInfo.StorageTypeEnum.FILE, List.of("file://", "/", "*")),
                "file://tmp")
            .build());

    PolarisPassthroughResolutionView passthroughView =
        new PolarisPassthroughResolutionView(
            callContext, entityManager, securityContext, CATALOG_NAME);
    this.catalog =
        new BasePolarisCatalog(
            entityManager,
            metaStoreManager,
            callContext,
            passthroughView,
            securityContext,
            authenticatedRoot,
            Mockito.mock(),
            new DefaultFileIOFactory());
    this.catalog.initialize(
        CATALOG_NAME,
        ImmutableMap.of(
            CatalogProperties.FILE_IO_IMPL, "org.apache.iceberg.inmemory.InMemoryFileIO"));
  }

  @AfterEach
  public void after() throws IOException {
    catalog().close();
    metaStoreManager.purge(polarisContext);
  }

  @Override
  protected BasePolarisCatalog catalog() {
    return catalog;
  }

  @Override
  protected Catalog tableCatalog() {
    return catalog;
  }

  @Override
  protected boolean requiresNamespaceCreate() {
    return true;
  }
}