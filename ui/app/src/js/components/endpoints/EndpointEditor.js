/*
 * Copyright (c) 2016 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

import AwsEndpointEditor from 'components/endpoints/aws/EndpointEditor'; //eslint-disable-line
import AzureEndpointEditor from 'components/endpoints/azure/EndpointEditor'; //eslint-disable-line
import VsphereEndpointEditor from 'components/endpoints/vsphere/EndpointEditor'; //eslint-disable-line
import EndpointEditorVue from 'components/endpoints/EndpointEditorVue.html';
import { EndpointsActions } from 'actions/Actions';
import constants from 'core/constants';
import services from 'core/services';
import utils from 'core/utils';

export default Vue.component('endpoint-editor', {
  template: EndpointEditorVue,
  props: {
    model: {
      required: true,
      type: Object
    },
    selectedEndpointType: {
      type: Object
    }
  },
  computed: {
    validationErrors() {
      return this.model.validationErrors || this.editorErrors || {};
    }
  },
  data() {
    return {
      adapters: utils.getAdapters(),
      certificate: null,
      certificateDetails: false,
      editor: {
        properties: this.model.item.endpointProperties || {},
        valid: false
      },
      editorErrors: null,
      endpointType: this.model.item.endpointType,
      endpointEditorType: null,
      name: this.model.item.name,
      saveDisabled: !this.model.item.documentSelfLink,
      verifyDisabled: !this.model.item.documentSelfLink,
      htmlEditor: {
        htmlEndpointEditorSrc: null,
        loaded: false
      }
    };
  },

  attached: function() {
    this.modelUnwatchVerified = this.$watch('model.verified', this.onChange);
    this.modelUnwatchSelectedEndpoint =
      this.$watch('selectedEndpointType', this.onSelectedEndpointChange);
  },

  detached: function() {
    this.modelUnwatchVerified();
    this.modelUnwatchSelectedEndpoint();
  },

  methods: {
    cancel($event) {
      $event.stopImmediatePropagation();
      $event.preventDefault();
      EndpointsActions.cancelEditEndpoint();
    },
    save($event) {
      $event.stopImmediatePropagation();
      $event.preventDefault();
      let toSave = this.getModel();
      if (toSave.documentSelfLink) {
        EndpointsActions.updateEndpoint(toSave);
      } else {
        EndpointsActions.createEndpoint(toSave);
      }
    },
    verify($event) {
      $event.stopImmediatePropagation();
      $event.preventDefault();
      this.certificate = null;
      let toSave = this.getModel();
      EndpointsActions.verifyEndpoint(toSave);
    },
    collectInventory($event) {
      $event.stopImmediatePropagation();
      $event.preventDefault();
      services.collectInventory(this.model.item).then(() => {
        this.editorErrors = {
          _valid: i18n.t('app.endpoint.edit.collectInventoryMessage')
        };
        setTimeout(() => {
          this.editorErrors = null;
        }, constants.VISUALS.ITEM_HIGHLIGHT_ACTIVE_TIMEOUT);
      });
    },
    collectImages($event) {
      $event.stopImmediatePropagation();
      $event.preventDefault();
      services.collectImages(this.model.item).then(() => {
        this.editorErrors = {
          _valid: i18n.t('app.endpoint.edit.collectImagesMessage')
        };
        setTimeout(() => {
          this.editorErrors = null;
        }, constants.VISUALS.ITEM_HIGHLIGHT_ACTIVE_TIMEOUT);
      });
    },
    onNameChange(name) {
      this.name = name;
      this.onChange();
    },
    onChange() {
      this.saveDisabled = this.isSaveDisabled();
      this.verifyDisabled = this.isVerifyDisabled();
    },
    onEndpointTypeChange(selectedEndpointType) {
      if (this.selectedEndpointType === selectedEndpointType) {
        return;
      }

      if (selectedEndpointType == null) {
        this.selectedEndpointType = null;
        return;
      }

      if (this.selectedEndpointType &&
        this.selectedEndpointType.id === selectedEndpointType.id) {
        return;
      }

      for (var i = 0; i < this.adapters.length; i++) {
        if (this.adapters[i].id === selectedEndpointType.id) {
          this.selectedEndpointType = this.adapters[i];
          return;
        }

      }

      this.selectedEndpointType = null;
    },
    // update the UI model
    onSelectedEndpointChange() {
      if (this.selectedEndpointType == null) {
        // reset the UI model used for visualization
        this.endpointType = null;
        this.endpointEditorType = null;
        return;
      }

      this.endpointType = this.selectedEndpointType.id;

      this.endpointEditorType = this.selectedEndpointType.endpointEditorType;
      if (this.selectedEndpointType.endpointEditorType === 'html') {
        var res = services.encodeSchemeAndHost(this.selectedEndpointType.endpointEditor);
        if (res) {
          this.htmlEditor.htmlEndpointEditorSrc = 'uerp/' + res;
        }
      }

      this.onChange();
    },
    onEditorChange(editor) {
      this.editor = editor;
      this.editorErrors = null;
      this.onChange();
    },
    onEditorError(errors) {
      this.editorErrors = errors;
    },
    isSaveDisabled() {
      return !this.name || !this.endpointType || !this.editor.valid ||
          !(this.model.verified && this.editor.valid);
    },
    isVerifyDisabled() {
      return !this.name || !this.endpointType || !this.editor.valid;
    },
    getModel() {
      var props;
      if (this.endpointEditorType === 'html') {
        var iframe = document.getElementById('htmlEndpointEditor');
        props = iframe.contentWindow.getModel();//htmlEndpointEditor
      } else {
        props = this.editor.properties;
      }
      return $.extend({}, this.model.item, {
        endpointProperties: $.extend({},
          this.model.item.endpointProperties || {}, props),
        endpointType: this.endpointType,
        name: this.name
      });
    },
    convertToObject(value) {
      if (value) {
        return {
          id: value,
          name: this.adapters.find((type) => type.id === value).name
        };
      }
    },
    htmlEditorInit(event) {
      var frame = event.target;
      services.mcpApi.htmlInit(frame, this.model.item, this);
      var body = frame.contentWindow.document.body;

      var height = Math.max(body.scrollHeight, body.offsetHeight);
      frame.height = height + 'px';

      this.htmlEditor.loaded = true;
    }
  }
});
