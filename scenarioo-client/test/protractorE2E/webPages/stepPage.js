'use strict';

var e2eUtils = require('../util/util.js'), BaseWebPage = require('./baseWebPage.js'), util = require('util');

function StepPage(overridePath) {
    if (overridePath && overridePath.length > 0) {
        BaseWebPage.call(this, overridePath);
    } else {
        BaseWebPage.call(this, '/');
    }

    this.stepNavigation = element(by.css('div.step-navigation'));

}

util.inherits(StepPage, BaseWebPage);

StepPage.prototype.assertPreviousStepIsDisabled = function() {
    this.stepNavigation.findElement(by.id('prevStepBtn')).then(function(element) {
        expect(element.isDisabled);
    });
} ;

StepPage.prototype.assertNextStepIsEnabled = function() {
    this.stepNavigation.findElement(by.id('nextStepBtn')).then(function(element) {
        expect(element.isEnabled());
    });
} ;

StepPage.prototype.assertPreviousPageIsDisabled = function() {
    this.stepNavigation.findElement(by.id('prevPageBtn')).then(function(element) {
        expect(element.isDisabled);
    });
};

StepPage.prototype.assertNextPageIsEnabled = function() {
    this.stepNavigation.findElement(by.id('nextPageBtn')).then(function(element) {
        expect(element.isEnabled());
    });
};

module.exports = StepPage;