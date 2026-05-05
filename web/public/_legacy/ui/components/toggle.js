export function bindToggleComponents(root = document) {
  root.querySelectorAll('.toggle input[type="checkbox"]').forEach((input) => {
    if (input.dataset.toggleBound === '1') return;
    input.dataset.toggleBound = '1';
    const wrapper = input.closest('.toggle');
    const sync = () => {
      if (!wrapper) return;
      wrapper.dataset.checked = input.checked ? '1' : '0';
    };
    input.addEventListener('change', sync);
    sync();
  });
}
