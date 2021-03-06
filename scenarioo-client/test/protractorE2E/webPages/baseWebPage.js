'use strict';

var e2eUtils = require('../util/util.js');


function BaseWebPage(path) {
    this.path = path;
}

BaseWebPage.prototype.assertPageIsDisplayed = function () {
    e2eUtils.assertRoute(this.path);
};

BaseWebPage.prototype.assertRoute = function (expectedUrl) {
    e2eUtils.assertRoute(expectedUrl);
};


BaseWebPage.prototype.clickBrowserBackButton = function () {
    e2eUtils.clickBrowserBackButton();
};

BaseWebPage.prototype.assertElementIsEnabled = function (elementId) {
    var htmlElement = this.stepNavigation.element(by.id(elementId));
    expect(htmlElement.isEnabled());
};

BaseWebPage.prototype.assertElementIsDisabled = function (elementId) {
    expect(this.stepNavigation.element(by.id(elementId)).isEnabled()).toBeFalsy();
};

BaseWebPage.prototype.clickElementById = function (elementId) {
    element(by.id(elementId)).click();
};

BaseWebPage.prototype.type = function (value) {
    element(by.css('body')).sendKeys(value);
};

/**
 * Navigate browser to a speficied path. By default (if not parameter is speficied) the path of the Page Object isused.
 *
 * @param path Overrides the default path of the page object. Specify only the part behind the # character.
 */
BaseWebPage.prototype.goToPage = function (path) {
    var targetPath;

    if (arguments.length === 1) {
        targetPath = path;
    } else {
        targetPath = this.path;
    }

    e2eUtils.getRoute(targetPath);
};

/**
 * Deprecated: same effect as startScenariooRevisited()
 */
BaseWebPage.prototype.initLocalStorage = function () {
    e2eUtils.initLocalStorage();
};

/**
 * Start scenarioo as a user that has never visited it before (visited cookie will not be set).
 */
BaseWebPage.prototype.startScenariooFirstTimeVisit = function () {
    e2eUtils.clearLocalStorage();
    e2eUtils.refreshBrowser(); // reload needed to restart without cookies.
};

/**
 * Start scenarioo as a user that has allready visited it before (visited cookie will be set).
 */
BaseWebPage.prototype.startScenariooRevisited = function() {
    e2eUtils.initLocalStorage();
};

module.exports = BaseWebPage;
