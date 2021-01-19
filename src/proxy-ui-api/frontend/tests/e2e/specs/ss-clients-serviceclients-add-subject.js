/*
 * The MIT License
 * Copyright (c) 2019- Nordic Institute for Interoperability Solutions (NIIS)
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

const getPages = (browser) => {
  const frontPage = browser.page.ssFrontPage();
  const mainPage = browser.page.ssMainPage();
  const serviceClientsPage = browser.page.serviceClients.serviceClientsPage();
  const addSubjectMemberStepPage = browser.page.serviceClients.addSubjectMemberStepPage();
  const clientInfo = mainPage.section.clientInfo;
  return {
    browser,
    frontPage,
    mainPage,
    serviceClientsPage,
    addSubjectMemberStepPage,
    clientInfo,
    clientsTab: mainPage.section.clientsTab,
    clientServices: clientInfo.section.services,
    serviceDetails: mainPage.section.serviceDetails,
  };
};

const signinToClientsTab = (pages) => {
  const { browser, frontPage, mainPage, clientsTab } = pages;
  // Open SUT and check that page is loaded
  frontPage.navigate();
  browser.waitForElementVisible('//*[@id="app"]');
  // Enter valid credentials
  frontPage.signinDefaultUser();
  mainPage.openClientsTab();
  browser.waitForElementVisible(clientsTab);
};

const setupServices = (pages) => {
  const {
    browser,
    frontPage,
    mainPage,
    clientInfo,
    clientsTab,
    clientServices,
  } = pages;

  // Add wsdl
  frontPage.navigate();
  browser.waitForElementVisible('//*[@id="app"]');
  clientsTab.openTestService();
  browser.waitForElementVisible(clientInfo);
  clientInfo.openServicesTab();
  browser.waitForElementVisible(clientServices);
  clientServices.openAddWSDL();
  clientServices.enterServiceUrl(
    browser.globals.testdata + '/' + browser.globals.wsdl_url_1,
  );
  clientServices.confirmAddDialog();
  browser.assert.containsText(
    clientServices.elements.serviceDescription,
    browser.globals.testdata + '/' + browser.globals.wsdl_url_1,
  );
  mainPage.closeSnackbar();
};

const clearServices = (pages) => {
  const {
    browser,
    frontPage,
    clientInfo,
    clientServices,
    clientsTab,
    serviceDetails,
    mainPage,
  } = pages;
  // Remove WSDL service description
  frontPage.navigate();
  browser.waitForElementVisible('//*[@id="app"]');
  clientsTab.openTestService();
  browser.waitForElementVisible(clientInfo);
  clientInfo.openServicesTab();
  browser.waitForElementVisible(clientServices);
  clientServices.openServiceDetails();
  browser.waitForElementVisible(serviceDetails);
  serviceDetails.deleteService();
  serviceDetails.confirmDelete();
  browser.assert.containsText(
    mainPage.elements.snackBarMessage,
    'Service description deleted',
  );
  mainPage.closeSnackbar();
};

const navigateToAddSubjectDialog = (pages) => {
  const {
    browser,
    frontPage,
    clientInfo,
    clientsTab,
    serviceClientsPage,
    addSubjectMemberStepPage,
  } = pages;
  frontPage.navigate();
  browser.waitForElementVisible('//*[@id="app"]');
  clientsTab.openTestService();
  browser.waitForElementVisible(clientInfo);
  // Navigate to service clients -tab
  clientInfo.openServiceClientsTab();
  browser.waitForElementVisible(serviceClientsPage.section.serviceClientsTab);

  // Verify buttons are visible
  browser.expect.element(serviceClientsPage.elements.addServiceClientButton).to
    .be.visible;
  browser.expect.element(serviceClientsPage.elements.unregisterButton).to.be
    .visible;
  // Navigate to Add Subjects dialog
  serviceClientsPage.openAddServiceClient();
  browser.waitForElementVisible(
    addSubjectMemberStepPage.elements.addSubjectWizardHeader,
  );
};

module.exports = {
  tags: ['addServiceClientSubject'],
  'Security server service clients Add Subject dialog cancel button works': (
    browser,
  ) => {
    const pages = getPages(browser);
    const { addSubjectMemberStepPage } = pages;
    signinToClientsTab(pages);
    navigateToAddSubjectDialog(pages);
    addSubjectMemberStepPage.cancel();
    browser.expect.element(
      '//h1[contains(@class, "identifier-wrap")][contains(text(), "TestService")]',
    ).to.be.visible;
    browser.end();
  },
  'Security server service clients Add Subject dialog filters subjects': (
    browser,
  ) => {
    const pages = getPages(browser);
    const { addSubjectMemberStepPage } = pages;
    signinToClientsTab(pages);
    navigateToAddSubjectDialog(pages);

    // Check that all subjects exist
    addSubjectMemberStepPage
      .verifySubjectListRow(1, '1122')
      .verifySubjectListRow(2, 'bac')
      .verifySubjectListRow(3, '2233')
      .verifySubjectListRow(4, 'abb')
      .verifySubjectListRow(5, 'cbb')
      .verifySubjectListRow(6, '1212')
      .verifySubjectListRow(7, 'security-server-owners')
      .verifySubjectListRow(8, 'TestClient')
      .verifySubjectListRow(9, 'TestService')
      .verifySubjectListRow(10, 'Management');
    // Filter subjects in Add Subjects dialog
    addSubjectMemberStepPage
      .setFilter('TestSe')
      .verifyVisibleId('TestService')
      .verifyNotPresentId('1122')
      .verifyNotPresentId('bac')
      .verifyNotPresentId('2233')
      .verifyNotPresentId('abb')
      .verifyNotPresentId('cbb')
      .verifyNotPresentId('1212')
      .verifyNotPresentId('security-server-owners')
      .verifyNotPresentId('TestClient')
      .verifyNotPresentId('Management');
    addSubjectMemberStepPage
      .setFilter('')
      .verifySubjectListRow(1, '1122')
      .verifySubjectListRow(2, 'bac')
      .verifySubjectListRow(3, '2233')
      .verifySubjectListRow(4, 'abb')
      .verifySubjectListRow(5, 'cbb')
      .verifySubjectListRow(6, '1212')
      .verifySubjectListRow(7, 'security-server-owners')
      .verifySubjectListRow(8, 'TestClient')
      .verifySubjectListRow(9, 'TestService')
      .verifySubjectListRow(10, 'Management');
    browser.end();
  },
  'Security server service clients Add Subject dialog only allows to select one ID from the list': (
    browser,
  ) => {
    const pages = getPages(browser);
    const { serviceClientsPage } = pages;
    signinToClientsTab(pages);
    navigateToAddSubjectDialog(pages);
    // Setup services to proceed in the dialog
    setupServices(pages);
    // Clear services
    clearServices(pages);
    browser.end();
  },
};
