export function bindFieldInlineComponents(root = document) {
  root.querySelectorAll('.field-inline').forEach((node) => {
    if (node.dataset.inlineBound === '1') return;
    node.dataset.inlineBound = '1';
    node.classList.add('field-inline-ready');
  });
}
