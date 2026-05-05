export function createWelcomeSchema({ renderWelcomePreview }) {
  return {
    fields: [
      { id: 'w_enabled', path: 'welcome.enabled', type: 'checked', default: false },
      { id: 'w_channelId', path: 'welcome.channelId', type: 'value', default: '' },
      { id: 'w_title', path: 'welcome.title', type: 'value', default: '' },
      { id: 'w_message', path: 'welcome.message', type: 'value', default: '' },
      { id: 'w_thumbnailUrl', path: 'welcome.thumbnailUrl', type: 'value', default: '' },
      { id: 'w_imageUrl', path: 'welcome.imageUrl', type: 'value', default: '' }
    ],
    afterReset() {
      renderWelcomePreview();
    },
    afterPopulate() {
      renderWelcomePreview();
    }
  };
}
