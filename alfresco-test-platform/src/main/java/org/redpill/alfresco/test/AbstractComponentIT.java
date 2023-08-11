/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redpill.alfresco.test;

import org.alfresco.model.ContentModel;
import org.alfresco.rad.test.AbstractAlfrescoIT;
import org.alfresco.rad.test.AlfrescoTestRunner;
import org.alfresco.repo.model.Repository;
import org.alfresco.repo.policy.BehaviourFilter;
import org.alfresco.repo.policy.PolicyComponent;
import org.alfresco.repo.security.authentication.AuthenticationComponent;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.repo.security.authentication.AuthenticationUtil.RunAsWork;
import org.alfresco.repo.site.SiteModel;
import org.alfresco.repo.transaction.RetryingTransactionHelper;
import org.alfresco.repo.transaction.RetryingTransactionHelper.RetryingTransactionCallback;
import org.alfresco.service.cmr.dictionary.DictionaryService;
import org.alfresco.service.cmr.model.FileExistsException;
import org.alfresco.service.cmr.model.FileFolderService;
import org.alfresco.service.cmr.model.FileInfo;
import org.alfresco.service.cmr.model.FileNotFoundException;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.cmr.search.SearchService;
import org.alfresco.service.cmr.security.*;
import org.alfresco.service.cmr.site.SiteInfo;
import org.alfresco.service.cmr.site.SiteService;
import org.alfresco.service.cmr.site.SiteVisibility;
import org.alfresco.service.cmr.workflow.WorkflowService;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.alfresco.service.transaction.TransactionService;
import org.alfresco.util.PropertyMap;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.*;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

@RunWith(value = AlfrescoTestRunner.class)
public abstract class AbstractComponentIT extends AbstractAlfrescoIT {

    private static final Logger LOG = Logger.getLogger(AbstractComponentIT.class);

    private final static String NAMESPACE_BEGIN = "" + QName.NAMESPACE_BEGIN;

    private boolean _requiresNew = false;

    protected AuthenticationComponent authenticationComponent;
    protected TransactionService transactionService;

    protected static RetryingTransactionHelper transactionHelper;
    protected BehaviourFilter behaviourFilter;
    protected Repository repository;
    protected SiteService siteService;
    protected MutableAuthenticationService authenticationService;
    protected PersonService personService;
    protected NodeService nodeService;
    protected FileFolderService fileFolderService;
    protected NamespaceService namespaceService;
    protected ContentService contentService;
    protected WorkflowService workflowService;
    protected AuthorityService authorityService;

    protected PermissionService permissionService;
    protected Properties properties;

    protected OwnableService ownableService;
    protected SearchService searchService;
    protected DictionaryService dictionaryService;
    protected PolicyComponent policyComponent;

    @Before
    public void setUpAbstract() {
        nodeService = getServiceRegistry().getNodeService();
        searchService = getServiceRegistry().getSearchService();
        dictionaryService = getServiceRegistry().getDictionaryService();
        ownableService = getServiceRegistry().getOwnableService();
        policyComponent = getServiceRegistry().getPolicyComponent();
        permissionService = getServiceRegistry().getPermissionService();
        authorityService = getServiceRegistry().getAuthorityService();
        workflowService = getServiceRegistry().getWorkflowService();
        contentService = getServiceRegistry().getContentService();
        namespaceService = getServiceRegistry().getNamespaceService();
        fileFolderService = getServiceRegistry().getFileFolderService();
        personService = getServiceRegistry().getPersonService();
        authenticationService = getServiceRegistry().getAuthenticationService();
        siteService = getServiceRegistry().getSiteService();
        transactionService = getServiceRegistry().getTransactionService();

        transactionHelper = transactionService.getRetryingTransactionHelper();

        ApplicationContext ctx = getApplicationContext();
        properties = (Properties) ctx.getBean("global-properties");
        repository = (Repository) ctx.getBean("repositoryHelper");
        behaviourFilter = (BehaviourFilter) ctx.getBean("policyBehaviourFilter");
        authenticationComponent = (AuthenticationComponent) ctx.getBean("authenticationComponent");
    }

    /**
     * Creates a user and a related person in the repository.
     *
     * @param userId
     * @return
     */
    protected NodeRef createUser(String userId) {
        return createUser(userId, null);
    }

    /**
     * Creates a user and a related person in the repository.
     *
     * @param userId
     * @param callback
     * @return
     */
    protected NodeRef createUser(final String userId, final CreateUserCallback callback) {
        return transactionHelper.doInTransaction(new RetryingTransactionCallback<NodeRef>() {
            @Override
            public NodeRef execute() throws Throwable {
                return AuthenticationUtil.runAsSystem(new RunAsWork<NodeRef>() {

                    @Override
                    public NodeRef doWork() throws Exception {
                        if (!authenticationService.authenticationExists(userId)) {
                            authenticationService.createAuthentication(userId, "password".toCharArray());
                            PropertyMap properties = new PropertyMap(3);
                            properties.put(ContentModel.PROP_USERNAME, userId);
                            properties.put(ContentModel.PROP_FIRSTNAME, userId);
                            properties.put(ContentModel.PROP_LASTNAME, "Test");
                            properties.put(ContentModel.PROP_EMAIL, AbstractComponentIT.this.properties.getProperty("mail.to.default"));

                            NodeRef user = personService.createPerson(properties);

                            if (callback != null) {
                                callback.onCreateUser(user);
                            }

                            return user;
                        } else {
                            fail("User exists: " + userId);
                            return null;
                        }
                    }

                });
            }
        }, false, _requiresNew);
    }

    protected Void setSiteMembership(final String shortName, final String authorityName, final String role) {
        return transactionHelper.doInTransaction(new RetryingTransactionCallback<Void>() {
            @Override
            public Void execute() throws Throwable {
                return AuthenticationUtil.runAsSystem(new RunAsWork<Void>() {
                    @Override
                    public Void doWork() throws Exception {
                        siteService.setMembership(shortName, authorityName, role);
                        return null;
                    }
                });
            }
        }, false, _requiresNew);
    }

    protected NodeRef getSiteDoclib(final String shortName) {
        return getSiteDoclib(shortName, true);
    }

    protected NodeRef getSiteDoclib(final String shortName, final boolean runAsSystem) {
        if (runAsSystem) {
            return transactionHelper.doInTransaction(new RetryingTransactionCallback<NodeRef>() {
                @Override
                public NodeRef execute() throws Throwable {
                    return AuthenticationUtil.runAsSystem(new RunAsWork<NodeRef>() {
                        @Override
                        public NodeRef doWork() throws Exception {
                            NodeRef documentLibrary = siteService.getContainer(shortName, SiteService.DOCUMENT_LIBRARY);
                            if (documentLibrary == null) {
                                documentLibrary = siteService.createContainer(shortName, SiteService.DOCUMENT_LIBRARY, ContentModel.TYPE_FOLDER, null);
                            }
                            return documentLibrary;
                        }
                    });
                }
            }, false, _requiresNew);
        } else {
            return transactionHelper.doInTransaction(new RetryingTransactionCallback<NodeRef>() {
                @Override
                public NodeRef execute() throws Throwable {
                    NodeRef documentLibrary = siteService.getContainer(shortName, SiteService.DOCUMENT_LIBRARY);
                    if (documentLibrary == null) {
                        documentLibrary = siteService.createContainer(shortName, SiteService.DOCUMENT_LIBRARY, ContentModel.TYPE_FOLDER, null);
                    }
                    return documentLibrary;
                }
            }, false, _requiresNew);
        }
    }

    protected void deleteUser(final NodeRef personRef) {
        transactionHelper.doInTransaction(new RetryingTransactionCallback<Void>() {
            @Override
            public Void execute() throws Throwable {
                AuthenticationUtil.runAsSystem(new RunAsWork<Void>() {

                    @Override
                    public Void doWork() throws Exception {
                        personService.deletePerson(personRef);

                        return null;
                    }

                });
                return null;
            }
        }, false, _requiresNew);
    }

    protected void deleteUser(final String userName) {
        transactionHelper.doInTransaction(new RetryingTransactionCallback<Void>() {
            @Override
            public Void execute() throws Throwable {
                AuthenticationUtil.runAsSystem(new RunAsWork<Void>() {

                    @Override
                    public Void doWork() throws Exception {
                        personService.deletePerson(userName);

                        return null;
                    }
                });
                return null;
            }
        }, false, _requiresNew);
    }

    /**
     * Creates a site and makes sure that a document library and a data list
     * container exist.
     *
     * @param preset
     * @param visibility
     * @param siteType
     * @return
     */
    protected SiteInfo createSite(final String preset, final String siteName, final SiteVisibility visibility, final QName siteType, final CreateSiteCallback callback) {
        return transactionHelper.doInTransaction(new RetryingTransactionCallback<SiteInfo>() {

            @Override
            public SiteInfo execute() throws Throwable {
                // Create site
                String name = null;

                if (siteName == null) {
                    name = "it-" + System.currentTimeMillis();
                } else {
                    name = siteName;
                }

                SiteInfo site = siteService.createSite(preset, name, name, name, visibility, siteType);

                if (callback != null) {
                    callback.onCreateSite(site);
                }

                assertNotNull(site);

                nodeService.addAspect(site.getNodeRef(), ContentModel.ASPECT_TEMPORARY, null);

                // Create document library container
                NodeRef documentLibrary = siteService.getContainer(name, SiteService.DOCUMENT_LIBRARY);

                if (documentLibrary == null) {
                    documentLibrary = siteService.createContainer(name, SiteService.DOCUMENT_LIBRARY, ContentModel.TYPE_FOLDER, null);
                }

                assertNotNull(documentLibrary);

                return site;
            }

        }, false, _requiresNew);
    }

    protected SiteInfo createSite(final String preset) {
        return transactionHelper.doInTransaction(new RetryingTransactionCallback<SiteInfo>() {

            @Override
            public SiteInfo execute() throws Throwable {
                return createSite(preset, null, SiteVisibility.PRIVATE, SiteModel.TYPE_SITE, null);
            }

        }, false, _requiresNew);
    }

    protected SiteInfo createSite() {
        return createSite("site-dashboard");
    }

    protected void deleteSite(SiteInfo siteInfo) {
        deleteSite(siteInfo, null);
    }

    protected void deleteSite(final SiteInfo siteInfo, final BeforeDeleteSiteCallback callback) {
        transactionHelper.doInTransaction(new RetryingTransactionCallback<Void>() {

            @Override
            public Void execute() throws Throwable {
                String fullyAuthenticatedUser = AuthenticationUtil.getFullyAuthenticatedUser();
                AuthenticationUtil.setAdminUserAsFullyAuthenticatedUser();

                if (callback != null) {
                    callback.beforeDeleteSite(siteInfo);
                }

                deleteLingeringSiteGroups(siteInfo);
                siteService.deleteSite(siteInfo.getShortName());

                System.out.println("deleted site with shortName: " + siteInfo.getShortName());
                AuthenticationUtil.setFullyAuthenticatedUser(fullyAuthenticatedUser);
                return null;
            }

        }, false, _requiresNew);

    }

    protected void deleteLingeringSiteGroups(final SiteInfo siteInfo) {
        transactionHelper.doInTransaction(new RetryingTransactionCallback<Void>() {

            @Override
            public Void execute() throws Throwable {
                final NodeRef nodeRef = siteInfo.getNodeRef();
                final QName siteType = nodeService.getType(nodeRef);
                final String shortName = siteInfo.getShortName();

                // Delete the associated groups
                AuthenticationUtil.runAs(new RunAsWork<Object>() {
                    public Void doWork() throws Exception {
                        // Delete the master site group
                        final String siteGroup = siteService.getSiteGroup(shortName);
                        if (authorityService.authorityExists(siteGroup)) {
                            authorityService.deleteAuthority(siteGroup, false);

                            // Iterate over the role related groups and delete then
                            Set<String> permissions = permissionService.getSettablePermissions(siteType);
                            for (String permission : permissions) {
                                String siteRoleGroup = siteService.getSiteRoleGroup(shortName, permission);

                                // Delete the site role group
                                authorityService.deleteAuthority(siteRoleGroup);
                            }
                        }

                        return null;
                    }
                }, AuthenticationUtil.getSystemUserName());
                return null;
            }

        }, false, _requiresNew);
    }

    protected FileInfo uploadDocument(SiteInfo site, String filename) {
        return uploadDocument(site, filename, null);
    }

    protected FileInfo uploadDocument(SiteInfo site, String filename, InputStream inputStream) {
        return uploadDocument(site, filename, inputStream, null);
    }

    protected FileInfo uploadDocument(SiteInfo site, String filename, InputStream inputStream, List<String> folders) {
        return uploadDocument(site, filename, inputStream, folders, null);
    }

    protected FileInfo uploadDocument(SiteInfo site, String filename, InputStream inputStream, List<String> folders, String name) {
        return uploadDocument(site, filename, inputStream, folders, name, null);
    }

    protected FileInfo uploadDocument(SiteInfo site, String filename, InputStream inputStream, List<String> folders, String name, NodeRef parentNodeRef) {
        return uploadDocument(site, filename, inputStream, folders, name, parentNodeRef, null);
    }

    protected FileInfo uploadDocument(SiteInfo site, String filename, InputStream inputStream, List<String> folders, String name, NodeRef parentNodeRef, String type) {
        return uploadDocument(site, filename, inputStream, folders, name, parentNodeRef, type, null);
    }

    protected FileInfo uploadDocument(final SiteInfo site, final String filename, final InputStream inputStream, final List<String> folders, final String name, final NodeRef parentNodeRef,
                                      final String type, final Map<QName, Serializable> properties) {
        return transactionHelper.doInTransaction(new RetryingTransactionCallback<FileInfo>() {

            @Override
            public FileInfo execute() throws Throwable {
                LOG.trace("Uploading " + filename);
                String finalName = StringUtils.isNotEmpty(name) ? name : FilenameUtils.getName(filename);

                // gets the folder to create the document in, if it's a site then it's
                // the documentLibrary, if not then it's the user home
                NodeRef documentLibrary = site != null ? siteService.getContainer(site.getShortName(), SiteService.DOCUMENT_LIBRARY) : repository.getUserHome(repository.getPerson());

                NodeRef finalParentNodeRef = parentNodeRef != null ? parentNodeRef : documentLibrary;

                if (folders != null) {
                    for (String folder : folders) {
                        FileInfo folderInfo = fileFolderService.create(finalParentNodeRef, folder, ContentModel.TYPE_FOLDER);
                        finalParentNodeRef = folderInfo.getNodeRef();
                    }
                }

                QName nodeTypeQName = type == null ? ContentModel.TYPE_CONTENT : createQName(type);

                QName assocQName = QName.createQName(NamespaceService.CONTENT_MODEL_1_0_URI, QName.createValidLocalName(finalName));

                Map<QName, Serializable> creationProperites = properties != null ? properties : new HashMap<QName, Serializable>();

                if (!creationProperites.containsKey(ContentModel.PROP_NAME)) {
                    creationProperites.put(ContentModel.PROP_NAME, finalName);
                }

                LOG.trace("Node type: " + nodeTypeQName);

                // creates the document
                final NodeRef document = nodeService.createNode(finalParentNodeRef, ContentModel.ASSOC_CONTAINS, assocQName, nodeTypeQName, creationProperites).getChildRef();
                assertNotNull("Created node is null!", document);
                // set the correct owner on the document
                AuthenticationUtil.runAsSystem(new RunAsWork<Void>() {

                    @Override
                    public Void doWork() throws Exception {
                        String username = AuthenticationUtil.getFullyAuthenticatedUser();

                        LOG.debug("Setting owner of '" + document + "' to '" + username + "'");

                        ownableService.setOwner(document, username);

                        return null;
                    }

                });

                FileInfo fileInfo = fileFolderService.getFileInfo(document);

                ContentWriter writer = contentService.getWriter(document, ContentModel.PROP_CONTENT, true);

                writer.guessEncoding();

                writer.guessMimetype(filename);

                InputStream content = inputStream != null ? inputStream : getClass().getResourceAsStream(filename);

                try {
                    if (content != null && content.available() > 0) {
                        writer.putContent(content);
                    } else {
                        throw new IOException("Empty content stream. Usually a consequence of that the file could not be read.");
                    }
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                } finally {
                    IOUtils.closeQuietly(content);
                }
                LOG.trace("Upload finished ");
                return fileInfo;
            }
        }, false, _requiresNew);
    }

    protected QName createQName(String s) {
        QName qname;
        if (s.indexOf(NAMESPACE_BEGIN) != -1) {
            qname = QName.createQName(s);
        } else {
            qname = QName.createQName(s, namespaceService);
        }
        return qname;
    }

    public <R> R runInSite(RunInSite<R> runInSite) {
        SiteInfo site = createSite();

        try {
            return runInSite.doInSite(site);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        } finally {
            deleteSite(site);

        }
    }

    public interface RunInSite<Result> {

        Result doInSite(SiteInfo site) throws Exception;

    }

    public void setRequiresNew(boolean requiresNew) {
        _requiresNew = requiresNew;
    }

    public boolean isRequiresNew() {
        return _requiresNew;

    }

    public interface CreateUserCallback {

        void onCreateUser(NodeRef user);

    }

    public interface BeforeDeleteSiteCallback {

        void beforeDeleteSite(SiteInfo siteInfo);

    }

    public interface CreateSiteCallback {

        void onCreateSite(SiteInfo site);

    }

    public void assertType(final String message, final NodeRef node, final QName type) {
        transactionHelper.doInTransaction(new RetryingTransactionCallback<Void>() {

            @Override
            public Void execute() throws Throwable {
                if (!nodeService.getType(node).isMatch(type)) {
                    fail(message);
                }
                return null;
            }
        }, false, _requiresNew);
    }

    public void assertType(NodeRef node, QName type) {
        assertType(null, node, type);
    }

    public void assertHasAspect(final String message, final NodeRef node, final QName aspect) {
        transactionHelper.doInTransaction(new RetryingTransactionCallback<Void>() {

            @Override
            public Void execute() throws Throwable {
                if (!nodeService.hasAspect(node, aspect)) {
                    fail(message);
                }
                return null;
            }
        }, false, _requiresNew);
    }

    public void assertHasAspect(NodeRef node, QName aspect) {
        assertHasAspect(null, node, aspect);
    }

    protected FileInfo copyNode(final NodeRef sourceNodeRef, final NodeRef targetParentRef, final String newName)
            throws FileExistsException, FileNotFoundException {
        return transactionHelper.doInTransaction(new RetryingTransactionCallback<FileInfo>() {
            @Override
            public FileInfo execute() throws Throwable {
                return fileFolderService.copy(sourceNodeRef, targetParentRef, newName);
            }
        }, false, isRequiresNew());
    }

}
