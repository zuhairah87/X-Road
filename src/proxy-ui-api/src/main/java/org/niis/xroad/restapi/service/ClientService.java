/**
 * The MIT License
 * Copyright (c) 2018 Estonian Information System Authority (RIA),
 * Nordic Institute for Interoperability Solutions (NIIS), Population Register Centre (VRK)
 * Copyright (c) 2015-2017 Estonian Information System Authority (RIA), Population Register Centre (VRK)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.niis.xroad.restapi.service;

import ee.ria.xroad.common.conf.serverconf.IsAuthentication;
import ee.ria.xroad.common.conf.serverconf.model.CertificateType;
import ee.ria.xroad.common.conf.serverconf.model.ClientType;
import ee.ria.xroad.common.conf.serverconf.model.LocalGroupType;
import ee.ria.xroad.common.conf.serverconf.model.ServerConfType;
import ee.ria.xroad.common.conf.serverconf.model.ServiceDescriptionType;
import ee.ria.xroad.common.identifier.ClientId;
import ee.ria.xroad.common.util.CryptoUtils;
import ee.ria.xroad.signer.protocol.dto.CertificateInfo;

import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.niis.xroad.restapi.cache.CurrentSecurityServerId;
import org.niis.xroad.restapi.cache.CurrentSecurityServerSignCertificates;
import org.niis.xroad.restapi.config.audit.AuditDataHelper;
import org.niis.xroad.restapi.config.audit.AuditEventLoggingFacade;
import org.niis.xroad.restapi.exceptions.DeviationAwareRuntimeException;
import org.niis.xroad.restapi.exceptions.ErrorDeviation;
import org.niis.xroad.restapi.exceptions.WarningDeviation;
import org.niis.xroad.restapi.facade.GlobalConfFacade;
import org.niis.xroad.restapi.repository.ClientRepository;
import org.niis.xroad.restapi.repository.IdentifierRepository;
import org.niis.xroad.restapi.util.ClientUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static ee.ria.xroad.common.conf.serverconf.model.ClientType.STATUS_DELINPROG;
import static ee.ria.xroad.common.conf.serverconf.model.ClientType.STATUS_GLOBALERR;
import static ee.ria.xroad.common.conf.serverconf.model.ClientType.STATUS_REGINPROG;
import static ee.ria.xroad.common.conf.serverconf.model.ClientType.STATUS_REGISTERED;
import static ee.ria.xroad.common.conf.serverconf.model.ClientType.STATUS_SAVED;

/**
 * client service
 */
@Slf4j
@Service
@Transactional
@PreAuthorize("isAuthenticated()")
public class ClientService {

    public static final String WARNING_UNREGISTERED_MEMBER = "unregistered_member";

    private final ClientRepository clientRepository;
    private final GlobalConfService globalConfService;
    private final GlobalConfFacade globalConfFacade;
    private final ServerConfService serverConfService;
    private final IdentifierRepository identifierRepository;
    private final ManagementRequestSenderService managementRequestSenderService;
    private final CurrentSecurityServerId currentSecurityServerId;
    private final AuditEventLoggingFacade auditEventLoggingFacade;
    private final AuditDataHelper auditDataHelper;


    // request scoped contains all certificates of type sign
    private final CurrentSecurityServerSignCertificates currentSecurityServerSignCertificates;

    /**
     * ClientService constructor
     */
    @Autowired
    public ClientService(ClientRepository clientRepository, GlobalConfFacade globalConfFacade,
            ServerConfService serverConfService, GlobalConfService globalConfService,
            IdentifierRepository identifierRepository, ManagementRequestSenderService managementRequestSenderService,
            CurrentSecurityServerId currentSecurityServerId,
            CurrentSecurityServerSignCertificates currentSecurityServerSignCertificates,
            AuditEventLoggingFacade auditEventLoggingFacade,
            AuditDataHelper auditDataHelper) {
        this.clientRepository = clientRepository;
        this.globalConfFacade = globalConfFacade;
        this.serverConfService = serverConfService;
        this.globalConfService = globalConfService;
        this.identifierRepository = identifierRepository;
        this.managementRequestSenderService = managementRequestSenderService;
        this.currentSecurityServerId = currentSecurityServerId;
        this.currentSecurityServerSignCertificates = currentSecurityServerSignCertificates;
        this.auditEventLoggingFacade = auditEventLoggingFacade;
        this.auditDataHelper = auditDataHelper;
    }

    /**
     * return all clients that exist on this security server
     * @return
     */
    public List<ClientType> getAllLocalClients() {
        return clientRepository.getAllLocalClients();
    }

    /**
     * return all members that exist on this security server.
     * There can only be 0, 1 or 2 members
     * @return
     */
    public List<ClientType> getAllLocalMembers() {
        return getAllLocalClients().stream()
                .filter(ct -> ct.getIdentifier().getSubsystemCode() == null)
                .collect(Collectors.toList());
    }

    /**
     * Return ClientId for all members who have clients on this instance.
     * For example if following clients exist:
     * - XRD:GOV:123 (owner member)
     * - XRD:GOV:123:SS1 (subsystem)
     * - XRD:COM:FOO:SS1 (subsystem)
     * method will return
     * - XRD:GOV:123 (owner member)
     * - XRD:COM:FOO (client subsystem's member)
     * @return
     */
    public Set<ClientId> getLocalClientMemberIds() {
        List<ClientType> allClients = getAllLocalClients();
        Set<ClientId> members = new HashSet<>();
        for (ClientType client : allClients) {
            ClientId id = client.getIdentifier();
            members.add(ClientId.create(id.getXRoadInstance(), id.getMemberClass(), id.getMemberCode()));
        }
        return members;
    }

    /**
     * return all global clients as ClientTypes
     * @return
     */
    public List<ClientType> getAllGlobalClients() {
        return globalConfFacade.getMembers()
                .stream()
                .map(memberInfo -> {
                    ClientType clientType = new ClientType();
                    clientType.setIdentifier(memberInfo.getId());
                    return clientType;
                })
                .collect(Collectors.toList());
    }

    /**
     * Return one client, or null if not found.
     * This method does NOT trigger load of lazy loaded properties.
     * Use {@code getLocalClientIsCerts}, {@code getLocalClientLocalGroups}, and
     * {@code getLocalClientServiceDescriptions} for that
     * @param id
     * @return the client, or null if matching client was not found
     */
    public ClientType getLocalClient(ClientId id) {
        ClientType clientType = clientRepository.getClient(id);
        return clientType;
    }

    /**
     * Returns clientType.getIsCert() that has been fetched with Hibernate.init.
     * @param id
     * @return list of CertificateTypes, or null if client does not exist
     */
    public List<CertificateType> getLocalClientIsCerts(ClientId id) {
        ClientType clientType = getLocalClient(id);
        if (clientType != null) {
            Hibernate.initialize(clientType.getIsCert());
            return clientType.getIsCert();
        }
        return null;
    }

    /**
     * Returns clientType.getServiceDescription() that has been fetched with Hibernate.init.
     * Also serviceDescription.services and serviceDescription.client.endpoints have been fetched.
     * @param id
     * @return list of ServiceDescriptionTypes, or null if client does not exist
     */
    public List<ServiceDescriptionType> getLocalClientServiceDescriptions(ClientId id) {
        ClientType clientType = getLocalClient(id);
        if (clientType != null) {
            for (ServiceDescriptionType serviceDescriptionType : clientType.getServiceDescription()) {
                Hibernate.initialize(serviceDescriptionType.getService());
            }
            Hibernate.initialize(clientType.getEndpoint());
            return clientType.getServiceDescription();
        }
        return null;
    }

    /**
     * Returns clientType.getLocalGroup() that has been fetched with Hibernate.init.
     * Also localGroup.groupMembers have been fetched.
     * @param id
     * @return list of LocalGroupTypes, or null if client does not exist
     */
    public List<LocalGroupType> getLocalClientLocalGroups(ClientId id) {
        ClientType clientType = getLocalClient(id);
        if (clientType != null) {
            for (LocalGroupType localGroupType : clientType.getLocalGroup()) {
                Hibernate.initialize(localGroupType.getGroupMember());
            }
            return clientType.getLocalGroup();
        }
        return null;
    }

    /**
     * Update connection type of an existing client
     * @param id
     * @param connectionType
     * @return
     * @throws IllegalArgumentException if connectionType was not supported value
     * @throws ClientNotFoundException if client was not found
     */
    public ClientType updateConnectionType(ClientId id, String connectionType) throws ClientNotFoundException {
        auditDataHelper.put(id);
        ClientType clientType = getLocalClientOrThrowNotFound(id);
        // validate connectionType param by creating enum out of it
        IsAuthentication enumValue = IsAuthentication.valueOf(connectionType);
        auditDataHelper.put(enumValue);
        clientType.setIsAuthentication(connectionType);
        clientRepository.saveOrUpdate(clientType);
        return clientType;
    }

    /**
     * Get a local client, throw exception if not found
     * @throws ClientNotFoundException if not found
     */
    public ClientType getLocalClientOrThrowNotFound(ClientId id) throws ClientNotFoundException {
        ClientType clientType = getLocalClient(id);
        if (clientType == null) {
            throw new ClientNotFoundException("client with id " + id + " not found");
        }
        return clientType;
    }

    /**
     * @param id
     * @param certBytes either PEM or DER -encoded certificate
     * @return created CertificateType with id populated
     * @throws CertificateException if certBytes was not a valid PEM or DER encoded certificate
     * @throws CertificateAlreadyExistsException if certificate already exists
     * @throws ClientNotFoundException if client was not found
     */
    public CertificateType addTlsCertificate(ClientId id, byte[] certBytes)
            throws CertificateException, CertificateAlreadyExistsException, ClientNotFoundException {
        auditDataHelper.put(id);
        X509Certificate x509Certificate;
        try {
            x509Certificate = CryptoUtils.readCertificate(certBytes);
        } catch (Exception e) {
            throw new CertificateException("cannot convert bytes to certificate", e);
        }
        String hash = calculateCertHexHash(x509Certificate);
        CertificateType certificateType = new CertificateType();
        try {
            certificateType.setData(x509Certificate.getEncoded());
        } catch (CertificateEncodingException ex) {
            // client cannot do anything about this
            throw new RuntimeException(ex);
        }
        auditDataHelper.put(certificateType);

        ClientType clientType = getLocalClientOrThrowNotFound(id);
        Optional<CertificateType> duplicate = clientType.getIsCert().stream()
                .filter(cert -> hash.equalsIgnoreCase(calculateCertHexHash(cert.getData())))
                .findFirst();
        if (duplicate.isPresent()) {
            throw new CertificateAlreadyExistsException("certificate already exists");
        }

        clientType.getIsCert().add(certificateType);
        clientRepository.saveOrUpdateAndFlush(clientType);
        return certificateType;
    }

    /**
     * Convenience / cleanness wrapper
     */
    private String calculateCertHexHash(X509Certificate cert) {
        try {
            return CryptoUtils.calculateCertHexHash(cert);
        } catch (Exception e) {
            throw new RuntimeException("hash calculation failed", e);
        }
    }

    /**
     * Convenience / cleanness wrapper
     */
    private String calculateCertHexHash(byte[] certBytes) {
        try {
            return CryptoUtils.calculateCertHexHash(certBytes);
        } catch (Exception e) {
            throw new RuntimeException("hash calculation failed", e);
        }
    }

    /**
     * Deletes one (and should be the only) certificate with
     * matching hash
     * @param id
     * @param certificateHash
     * @return
     * @throws ClientNotFoundException if client was not found
     * @throws CertificateNotFoundException if certificate was not found
     */
    public ClientType deleteTlsCertificate(ClientId id, String certificateHash)
            throws ClientNotFoundException, CertificateNotFoundException {

        auditDataHelper.put(id);

        ClientType clientType = getLocalClientOrThrowNotFound(id);
        Optional<CertificateType> certificateType = clientType.getIsCert().stream()
                .filter(certificate -> calculateCertHexHash(certificate.getData()).equalsIgnoreCase(certificateHash))
                .findAny();
        if (!certificateType.isPresent()) {
            throw new CertificateNotFoundException();
        }

        auditDataHelper.put(certificateType.get());

        clientType.getIsCert().remove(certificateType.get());
        clientRepository.saveOrUpdate(clientType);
        return clientType;
    }

    /**
     * Returns a single client tls certificate that has matching hash
     * @param id
     * @param certificateHash
     * @return
     * @throws ClientNotFoundException if client was not found
     */
    public Optional<CertificateType> getTlsCertificate(ClientId id, String certificateHash)
            throws ClientNotFoundException {
        ClientType clientType = getLocalClientOrThrowNotFound(id);
        Optional<CertificateType> certificateType = clientType.getIsCert().stream()
                .filter(certificate -> calculateCertHexHash(certificate.getData()).equalsIgnoreCase(certificateHash))
                .findAny();
        return certificateType;
    }

    /**
     * Find clients in the local serverconf
     * @param name
     * @param instance
     * @param propertyClass
     * @param memberCode
     * @param subsystemCode
     * @param showMembers include members (without susbsystemCode) in the results
     * @param localValidSignCert include only local clients that have valid sign cert
     * @return ClientType list
     */
    public List<ClientType> findLocalClients(String name, String instance, String propertyClass, String memberCode,
            String subsystemCode, boolean showMembers, boolean localValidSignCert) {
        Predicate<ClientType> matchingSearchTerms = buildClientSearchPredicate(name, instance, propertyClass,
                memberCode, subsystemCode, localValidSignCert);

        List<ClientType> allLocalClients = getAllLocalClients();

        return allLocalClients.stream()
                .filter(matchingSearchTerms)
                .filter(ct -> showMembers || ct.getIdentifier().getSubsystemCode() != null)
                .collect(Collectors.toList());
    }

    /**
     * Find clients in the globalconf and return them as new ClientTypes
     * @param name
     * @param instance
     * @param propertyClass
     * @param memberCode
     * @param subsystemCode
     * @param showMembers include members (without susbsystemCode) in the results
     * @return ClientType list
     */
    public List<ClientType> findGlobalClients(String name, String instance, String propertyClass, String memberCode,
            String subsystemCode, boolean showMembers) {
        Predicate<ClientType> matchingSearchTerms = buildClientSearchPredicate(name, instance, propertyClass,
                memberCode, subsystemCode, false);
        return getAllGlobalClients().stream()
                .filter(matchingSearchTerms)
                .filter(clientType -> showMembers || clientType.getIdentifier().getSubsystemCode() != null)
                .collect(Collectors.toList());
    }

    /**
     * Find client by ClientId
     * @param clientId
     * @return
     */
    public Optional<ClientType> findByClientId(ClientId clientId) {
        List<ClientType> localClients = getAllLocalClients();
        List<ClientType> globalClients = getAllGlobalClients();
        List<ClientType> distinctClients = mergeClientListsDistinctively(globalClients, localClients);
        return distinctClients.stream()
                .filter(clientType -> clientType.getIdentifier().toShortString().trim()
                        .equals(clientId.toShortString().trim()))
                .findFirst();
    }

    /**
     * Find from all clients (local or global)
     * @param name
     * @param instance
     * @param memberClass
     * @param memberCode
     * @param subsystemCode
     * @param showMembers include members (without subsystemCode) in the results
     * @param internalSearch search only in the local clients
     * @param localValidSignCert include only local clients that have valid sign cert
     * @param excludeLocal list only clients that are missing from this security server
     * @return ClientType list
     */
    public List<ClientType> findClients(String name, String instance, String memberClass, String memberCode,
            String subsystemCode, boolean showMembers, boolean internalSearch,
            boolean localValidSignCert, boolean excludeLocal) {

        List<ClientType> localClients = findLocalClients(name, instance, memberClass, memberCode, subsystemCode,
                showMembers, localValidSignCert);
        if (internalSearch || localValidSignCert) {
            return localClients;
        }

        List<ClientType> globalClients = findGlobalClients(name, instance, memberClass, memberCode, subsystemCode,
                showMembers);

        if (excludeLocal) {
            return subtractLocalFromGlobalClients(globalClients, localClients);
        }

        return mergeClientListsDistinctively(globalClients, localClients);
    }

    /**
     * Subtract clients in a list from another list
     *
     * @param globalClients
     * @param localClients
     * @return
     */
    private List<ClientType> subtractLocalFromGlobalClients(List<ClientType> globalClients,
            List<ClientType> localClients) {
        List<String> localClientIds = localClients.stream().map(localClient ->
                localClient.getIdentifier().toShortString()).collect(Collectors.toList());

        return globalClients.stream()
                .filter(globalClient -> !localClientIds.contains(globalClient.getIdentifier().toShortString()))
                .collect(Collectors.toList());
    }

    /**
     * Registers a client
     * @param clientId client to register
     * @throws GlobalConfOutdatedException
     * @throws ClientNotFoundException
     * @throws CannotRegisterOwnerException
     * @throws ActionNotPossibleException
     */
    public void registerClient(ClientId clientId) throws GlobalConfOutdatedException, ClientNotFoundException,
            CannotRegisterOwnerException, ActionNotPossibleException {

        auditDataHelper.put(clientId);

        ClientType client = getLocalClientOrThrowNotFound(clientId);
        ClientId ownerId = currentSecurityServerId.getServerId().getOwner();
        if (ownerId.equals(client.getIdentifier())) {
            throw new CannotRegisterOwnerException();
        }
        if (!client.getClientStatus().equals(ClientType.STATUS_SAVED)) {
            throw new ActionNotPossibleException("Only clients with status 'saved' can be registered");
        }
        try {
            Integer requestId = managementRequestSenderService.sendClientRegisterRequest(clientId);
            client.setClientStatus(ClientType.STATUS_REGINPROG);
            auditDataHelper.putClientStatus(client);
            auditDataHelper.putManagementRequestId(requestId);
            clientRepository.saveOrUpdate(client);
        } catch (ManagementRequestSendingFailedException e) {
            throw new DeviationAwareRuntimeException(e, e.getErrorDeviation());
        }
    }

    /**
     * Unregister a client
     * @param clientId client to unregister
     * @throws GlobalConfOutdatedException
     * @throws ClientNotFoundException
     * @throws CannotUnregisterOwnerException when trying to unregister the security server owner
     * @throws ActionNotPossibleException when trying do unregister a client that cannot be unregistered
     */
    public void unregisterClient(ClientId clientId) throws GlobalConfOutdatedException, ClientNotFoundException,
            CannotUnregisterOwnerException, ActionNotPossibleException {

        auditDataHelper.put(clientId);

        ClientType client = getLocalClientOrThrowNotFound(clientId);
        List<String> allowedStatuses = Arrays.asList(STATUS_REGISTERED, STATUS_REGINPROG);
        if (!allowedStatuses.contains(client.getClientStatus())) {
            throw new ActionNotPossibleException("cannot unregister client with status " + client.getClientStatus());
        }
        ClientId ownerId = currentSecurityServerId.getServerId().getOwner();
        if (clientId.equals(ownerId)) {
            throw new CannotUnregisterOwnerException();
        }
        try {
            Integer requestId = managementRequestSenderService.sendClientUnregisterRequest(clientId);
            auditDataHelper.putClientStatus(client);
            auditDataHelper.putManagementRequestId(requestId);
            client.setClientStatus(STATUS_DELINPROG);
            clientRepository.saveOrUpdate(client);
        } catch (ManagementRequestSendingFailedException e) {
            throw new DeviationAwareRuntimeException(e, e.getErrorDeviation());
        }
    }

    /**
     * Changes Security Server owner
     * @param memberClass member class of new owner
     * @param memberCode member code of new owner
     * @param subsystemCode should be null because only member can be owner
     * @throws GlobalConfOutdatedException
     * @throws ClientNotFoundException
     * @throws MemberAlreadyOwnerException
     * @throws ActionNotPossibleException
     */
    public void changeOwner(String memberClass, String memberCode, String subsystemCode) throws
            GlobalConfOutdatedException, ClientNotFoundException, MemberAlreadyOwnerException,
            ActionNotPossibleException {
        if (subsystemCode != null) {
            throw new ActionNotPossibleException("Only member can be an owner");
        }
        ClientId clientId = ClientId.create(globalConfFacade.getInstanceIdentifier(), memberClass, memberCode);
        auditDataHelper.put(clientId);
        ClientType client = getLocalClientOrThrowNotFound(clientId);
        auditDataHelper.putClientStatus(client);
        ClientId ownerId = currentSecurityServerId.getServerId().getOwner();
        if (ownerId.equals(client.getIdentifier())) {
            throw new MemberAlreadyOwnerException();
        }
        if (!client.getClientStatus().equals(STATUS_REGISTERED)) {
            throw new ActionNotPossibleException("Only member with status 'registered' can become owner");
        }

        try {
            Integer requestId = managementRequestSenderService.sendOwnerChangeRequest(clientId);
            auditDataHelper.putManagementRequestId(requestId);
        } catch (ManagementRequestSendingFailedException e) {
            throw new DeviationAwareRuntimeException(e, e.getErrorDeviation());
        }
    }

    /**
     * Merge two client lists into one with only unique clients. The distinct clients in the latter list
     * {@code moreClients} are favoured in the case of duplicates.
     * @param clients list of clients
     * @param moreClients list of clients (these will override the ones in {@code clients} in the case of duplicates)
     * @return
     */
    private List<ClientType> mergeClientListsDistinctively(List<ClientType> clients, List<ClientType> moreClients) {
        Map<String, ClientType> uniqueClientMap = new HashMap<>();
        // add clients into the HashMap with client identifier string as the key
        clients.forEach(clientType -> uniqueClientMap.put(clientType.getIdentifier().toShortString(), clientType));
        /*
          add other clients into the HashMap with client identifier string as the key
          this conveniently overwrites all duplicate keys
         */
        moreClients.forEach(clientType -> uniqueClientMap.put(clientType.getIdentifier().toShortString(), clientType));
        return new ArrayList<>(uniqueClientMap.values());
    }

    private Predicate<ClientType> buildClientSearchPredicate(String name, String instance,
            String memberClass, String memberCode, String subsystemCode, boolean localValidSignCert) {
        Predicate<ClientType> clientTypePredicate = clientType -> true;
        if (!StringUtils.isEmpty(name)) {
            clientTypePredicate = clientTypePredicate.and(ct -> {
                String memberName = globalConfFacade.getMemberName(ct.getIdentifier());
                return memberName != null && memberName.toLowerCase().contains(name.toLowerCase());
            });
        }
        if (!StringUtils.isEmpty(instance)) {
            clientTypePredicate = clientTypePredicate.and(ct -> ct.getIdentifier().getXRoadInstance().toLowerCase()
                    .contains(instance.toLowerCase()));
        }
        if (!StringUtils.isEmpty(memberClass)) {
            clientTypePredicate = clientTypePredicate.and(ct -> ct.getIdentifier().getMemberClass().toLowerCase()
                    .contains(memberClass.toLowerCase()));
        }
        if (!StringUtils.isEmpty(memberCode)) {
            clientTypePredicate = clientTypePredicate.and(ct -> ct.getIdentifier().getMemberCode().toLowerCase()
                    .contains(memberCode.toLowerCase()));
        }
        if (!StringUtils.isEmpty(subsystemCode)) {
            clientTypePredicate = clientTypePredicate.and(ct -> ct.getIdentifier().getSubsystemCode() != null
                    && ct.getIdentifier().getSubsystemCode().toLowerCase().contains(subsystemCode.toLowerCase()));
        }
        if (localValidSignCert) {
            clientTypePredicate = clientTypePredicate.and(this::hasValidLocalSignCertCheck);
        }
        return clientTypePredicate;
    }

    /**
     * Check whether client has valid local sign cert
     *
     * @param clientType
     * @return
     */
    private boolean hasValidLocalSignCertCheck(ClientType clientType) {
        List<CertificateInfo> signCertificateInfos = currentSecurityServerSignCertificates
                .getSignCertificateInfos();
        return ClientUtils.hasValidLocalSignCert(clientType.getIdentifier(), signCertificateInfos);
    }

    /**
     * Add a new client to this security server. Can add either a member or a subsystem.
     * Member (added client, or member associated with the client subsystem) can either
     * be one already registered to global conf, or an unregistered one. Unregistered one
     * can only be added with ignoreWarnings = true.
     *
     * Client is added to this instance, it is not possible to add clients who would have
     * different instance_id from this security server's instance.
     *
     * To prevent against two threads both creating "first" additional members,
     * synchronize access to this method on controller layer
     * (synchronizing this method does not help since transaction start & commit
     * are outside of this method).
     * @param memberClass member class of added client
     * @param memberCode member code of added client
     * @param subsystemCode subsystem code of added client (null if adding a member)
     * @param isAuthentication {@code IsAuthentication} value to set for the new client
     * @param ignoreWarnings if warning about unregistered member should be ignored
     * @return
     * @throws ClientAlreadyExistsException if client has already been added to security server
     * @throws AdditionalMemberAlreadyExistsException if tried to add a new member, and
     * security server already has owner member + one additional member
     * @throws UnhandledWarningsException if tried to add client associated with a member which
     * does not exist in global conf yet, and ignoreWarnings was false
     */
    public ClientType addLocalClient(String memberClass,
            String memberCode,
            String subsystemCode,
            IsAuthentication isAuthentication,
            boolean ignoreWarnings) throws ClientAlreadyExistsException,
            AdditionalMemberAlreadyExistsException, UnhandledWarningsException {

        ClientId clientId = ClientId.create(globalConfFacade.getInstanceIdentifier(),
                memberClass,
                memberCode,
                subsystemCode);


        auditDataHelper.put(clientId);
        auditDataHelper.put(isAuthentication);

        ClientType existingLocalClient = getLocalClient(clientId);
        ClientId ownerId = currentSecurityServerId.getServerId().getOwner();
        if (existingLocalClient != null) {
            throw new ClientAlreadyExistsException("client " + clientId + " already exists");
        }
        if (clientId.getSubsystemCode() == null) {
            // adding member - check that we dont already have owner + one additional member
            List<ClientType> existingMembers = getAllLocalMembers();
            Optional<ClientType> additionalMember = existingMembers.stream()
                    .filter(m -> !ownerId.equals(m.getIdentifier()))
                    .findFirst();
            if (additionalMember.isPresent()) {
                throw new AdditionalMemberAlreadyExistsException("additional member "
                        + additionalMember.get().getIdentifier() + " already exists");
            }
        }

        // check if the member associated with clientId exists in global conf
        ClientId memberId = clientId.getMemberId();
        if (globalConfFacade.getMemberName(memberId) == null) {
            // unregistered member
            if (!ignoreWarnings) {
                WarningDeviation warning = new WarningDeviation(WARNING_UNREGISTERED_MEMBER, memberId.toShortString());
                throw new UnhandledWarningsException(warning);
            }
        }

        boolean clientRegistered = globalConfService.isSecurityServerClientForThisInstance(clientId);
        ClientType client = new ClientType();
        client.setIdentifier(getPossiblyManagedEntity(clientId));
        if (clientRegistered) {
            client.setClientStatus(ClientType.STATUS_REGISTERED);
        } else {
            client.setClientStatus(ClientType.STATUS_SAVED);
        }
        auditDataHelper.putClientStatus(client);

        client.setIsAuthentication(isAuthentication.name());
        ServerConfType serverConfType = serverConfService.getServerConf();
        client.setConf(serverConfType);
        serverConfType.getClient().add(client);

        clientRepository.saveOrUpdate(client);
        return client;
    }

    /**
     * If ClientId already exists in DB, return the managed instance.
     * Otherwise return transient instance that was given as parameter
     */
    public ClientId getPossiblyManagedEntity(ClientId transientClientId) {
        ClientId managedEntity = identifierRepository.getClientId(transientClientId);
        if (managedEntity != null) {
            return managedEntity;
        } else {
            return transientClientId;
        }
    }

    /**
     * Delete a local client.
     * @param clientId
     * @throws ActionNotPossibleException if client status did not allow delete
     * @throws CannotDeleteOwnerException if attempted to delete
     * @throws ClientNotFoundException if local client with given id was not found
     */
    public void deleteLocalClient(ClientId clientId) throws ActionNotPossibleException,
            CannotDeleteOwnerException, ClientNotFoundException {

        auditDataHelper.put(clientId);

        ClientType clientType = getLocalClientOrThrowNotFound(clientId);
        // cant delete owner
        ClientId ownerId = currentSecurityServerId.getServerId().getOwner();
        if (ownerId.equals(clientType.getIdentifier())) {
            throw new CannotDeleteOwnerException();
        }
        // cant delete with statuses STATUS_REGINPROG and STATUS_REGISTERED
        List allowedStatuses = Arrays.asList(STATUS_SAVED, STATUS_DELINPROG, STATUS_GLOBALERR);
        if (!allowedStatuses.contains(clientType.getClientStatus())) {
            throw new ActionNotPossibleException("cannot delete client with status " + clientType.getClientStatus());
        }

        ServerConfType serverConfType = serverConfService.getServerConf();
        if (!serverConfType.getClient().remove(clientType)) {
            throw new RuntimeException("client to be deleted was somehow missing from serverconf");
        }
    }

    /**
     * Thrown when someone attempted to delete client who is this security
     * server's owner member
     */
    public static class CannotDeleteOwnerException extends ServiceException {
        public static final String ERROR_CANNOT_DELETE_OWNER = "cannot_delete_owner";

        public CannotDeleteOwnerException() {
            super(new ErrorDeviation(ERROR_CANNOT_DELETE_OWNER));
        }
    }

    /**
     * Thrown when client that already exists in server conf was tried to add
     */
    public static class ClientAlreadyExistsException extends ServiceException {
        public static final String ERROR_CLIENT_ALREADY_EXISTS = "client_already_exists";

        public ClientAlreadyExistsException(String s) {
            super(s, new ErrorDeviation(ERROR_CLIENT_ALREADY_EXISTS));
        }
    }

    /**
     * Thrown when someone tries to add another member, and an additional member besides
     * the owner member already exists (there can only be owner member + one additional member)
     */
    public static class AdditionalMemberAlreadyExistsException extends ServiceException {
        public static final String ERROR_ADDITIONAL_MEMBER_ALREADY_EXISTS = "additional_member_already_exists";

        public AdditionalMemberAlreadyExistsException(String s) {
            super(s, new ErrorDeviation(ERROR_ADDITIONAL_MEMBER_ALREADY_EXISTS));
        }
    }

    /**
     * Thrown when trying to register the owner member
     */
    public static class CannotRegisterOwnerException extends ServiceException {
        public static final String ERROR_CANNOT_REGISTER_OWNER = "cannot_register_owner";

        public CannotRegisterOwnerException() {
            super(new ErrorDeviation(ERROR_CANNOT_REGISTER_OWNER));
        }
    }

    /**
     * Thrown when trying to unregister the security server owner
     */
    public static class CannotUnregisterOwnerException extends ServiceException {
        public static final String CANNOT_UNREGISTER_OWNER = "cannot_unregister_owner";

        public CannotUnregisterOwnerException() {
            super(new ErrorDeviation(CANNOT_UNREGISTER_OWNER));
        }
    }

    /**
     * Thrown when trying to make the current owner the new owner
     */
    public static class MemberAlreadyOwnerException extends ServiceException {
        public static final String ERROR_CANNOT_MAKE_OWNER = "member_already_owner";

        public MemberAlreadyOwnerException() {
            super(new ErrorDeviation(ERROR_CANNOT_MAKE_OWNER));
        }
    }
}
