export function createNumberChainSchema() {
  return {
    fields: [
      { id: 'nc_enabled', path: 'numberChain.enabled', type: 'checked', default: false },
      { id: 'nc_channelId', path: 'numberChain.channelId', type: 'value', default: '' },
      { id: 'nc_nextNumber', path: 'numberChain.nextNumber', type: 'number', default: 1, persist: false }
    ]
  };
}
