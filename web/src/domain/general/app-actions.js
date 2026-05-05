export function createAppActionsModule(deps) {
  const {
    byId,
    api,
    t,
    selectedGuild,
    collectSettings,
    setValue,
    showStatus,
    showToast
  } = deps;

  async function sendWelcomePreview() {
    const guildId = selectedGuild();
    if (!guildId) return;
    const previewBtn = byId('sendWelcomePreviewBtn');
    if (previewBtn) previewBtn.disabled = true;
    try {
      const data = await api(`/api/guild/${guildId}/welcome/preview`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ welcome: collectSettings().welcome })
      });
      showStatus(data?.message || t('welcomePreviewSent'));
      showToast(data?.message || t('welcomePreviewSent'), 'success');
    } catch (e) {
      showStatus(e.message || t('welcomePreviewSendFailed'));
      showToast(e.message || t('welcomePreviewSendFailed'), 'error');
    } finally {
      if (previewBtn) previewBtn.disabled = false;
    }
  }

  async function resetNumberChainProgress() {
    const guildId = selectedGuild();
    if (!guildId) return;
    try {
      const data = await api(`/api/guild/${guildId}/number-chain/reset`, { method: 'POST' });
      setValue('nc_nextNumber', String(data?.nextNumber ?? 1));
      showStatus(t('numberChainResetSuccess'));
      showToast(t('numberChainResetSuccess'), 'success');
    } catch (e) {
      showStatus(e.message || t('numberChainResetFailed'));
      showToast(e.message || t('numberChainResetFailed'), 'error');
    }
  }

  return {
    sendWelcomePreview,
    resetNumberChainProgress
  };
}
