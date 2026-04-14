export function createCustomSelectComponent(select) {
  if (!select) return null;

  if (select.dataset.customSelectBound === '1') {
    return {
      refresh: () => select.dispatchEvent(new Event('custom-select:refresh'))
    };
  }
  select.dataset.customSelectBound = '1';

  let shell = select.closest('.custom-select-shell');
  if (!shell) {
    shell = document.createElement('div');
    shell.className = 'custom-select-shell';
    select.parentNode?.insertBefore(shell, select);
    shell.appendChild(select);
  }

  select.classList.add('custom-select-native');
  select.tabIndex = -1;
  select.setAttribute('aria-hidden', 'true');

  const trigger = document.createElement('button');
  trigger.type = 'button';
  trigger.className = 'select-trigger';
  trigger.setAttribute('aria-haspopup', 'listbox');
  trigger.setAttribute('aria-expanded', 'false');

  const triggerLabel = document.createElement('span');
  triggerLabel.className = 'select-trigger-label';
  const triggerCaret = document.createElement('span');
  triggerCaret.className = 'select-trigger-caret';
  triggerCaret.setAttribute('aria-hidden', 'true');

  trigger.append(triggerLabel, triggerCaret);

  const menu = document.createElement('div');
  menu.className = 'select-menu';
  menu.hidden = true;
  menu.setAttribute('role', 'listbox');
  menu.id = `${select.id || 'select'}-listbox`;
  trigger.id = `${menu.id}-trigger`;
  trigger.setAttribute('aria-controls', menu.id);
  menu.setAttribute('aria-labelledby', trigger.id);

  shell.append(trigger, menu);

  let optionButtons = [];
  let open = false;

  function getOptions() {
    return [...select.options].map((option, index) => ({
      index,
      value: option.value,
      label: option.textContent || option.value || '-',
      selected: option.selected,
      disabled: option.disabled
    }));
  }

  function closeMenu({ focusTrigger = false } = {}) {
    if (!open) return;
    open = false;
    shell.classList.remove('open');
    menu.hidden = true;
    trigger.setAttribute('aria-expanded', 'false');
    document.removeEventListener('mousedown', onDocumentPointerDown, true);
    document.removeEventListener('focusin', onDocumentFocusIn, true);
    document.removeEventListener('keydown', onDocumentKeyDown, true);
    if (focusTrigger) trigger.focus();
  }

  function focusOption(index) {
    const target = optionButtons[index];
    if (target && !target.disabled) {
      target.focus();
    }
  }

  function findEnabledOptionIndex(startIndex, step = 1) {
    for (let index = startIndex; index >= 0 && index < optionButtons.length; index += step) {
      if (!optionButtons[index]?.disabled) return index;
    }
    return -1;
  }

  function openMenu() {
    if (open || trigger.disabled) return;
    open = true;
    shell.classList.add('open');
    menu.hidden = false;
    trigger.setAttribute('aria-expanded', 'true');
    document.addEventListener('mousedown', onDocumentPointerDown, true);
    document.addEventListener('focusin', onDocumentFocusIn, true);
    document.addEventListener('keydown', onDocumentKeyDown, true);
    const selectedIndex = optionButtons.findIndex((button) => button.getAttribute('aria-selected') === 'true');
    const focusIndex = selectedIndex >= 0 && !optionButtons[selectedIndex]?.disabled
      ? selectedIndex
      : findEnabledOptionIndex(0, 1);
    focusOption(focusIndex);
  }

  function selectValue(value) {
    const changed = select.value !== value;
    if (changed) {
      select.value = value;
    }
    refresh();
    closeMenu({ focusTrigger: true });
    if (changed) {
      select.dispatchEvent(new Event('change', { bubbles: true }));
    }
  }

  function renderOptions() {
    const options = getOptions();
    optionButtons = [];
    menu.innerHTML = '';

    if (options.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'select-empty';
      empty.textContent = '-';
      menu.appendChild(empty);
      return;
    }

    options.forEach((option) => {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'select-option';
      button.id = `${menu.id}-option-${option.index}`;
      button.setAttribute('role', 'option');
      button.setAttribute('aria-selected', option.selected ? 'true' : 'false');
      button.setAttribute('aria-disabled', option.disabled ? 'true' : 'false');
      button.disabled = option.disabled;
      button.dataset.value = option.value;
      button.textContent = option.label;
      button.onclick = (event) => {
        event.preventDefault();
        event.stopPropagation();
        selectValue(option.value);
      };
      button.onkeydown = (event) => {
        const currentIndex = optionButtons.indexOf(button);
        if (event.key === 'ArrowDown') {
          event.preventDefault();
          focusOption(findEnabledOptionIndex(currentIndex + 1, 1));
        } else if (event.key === 'ArrowUp') {
          event.preventDefault();
          focusOption(findEnabledOptionIndex(currentIndex - 1, -1));
        } else if (event.key === 'Home') {
          event.preventDefault();
          focusOption(findEnabledOptionIndex(0, 1));
        } else if (event.key === 'End') {
          event.preventDefault();
          focusOption(findEnabledOptionIndex(optionButtons.length - 1, -1));
        } else if (event.key === 'Escape') {
          event.preventDefault();
          closeMenu({ focusTrigger: true });
        } else if (event.key === 'Tab') {
          closeMenu();
        }
      };
      optionButtons.push(button);
      menu.appendChild(button);
    });
  }

  function refresh() {
    const options = getOptions();
    renderOptions();
    const selected = options.find((option) => option.selected) || options[0];
    triggerLabel.textContent = selected?.label || '-';
    trigger.disabled = !!select.disabled || options.length === 0;
    shell.classList.toggle('is-disabled', trigger.disabled);
  }

  function onDocumentPointerDown(event) {
    if (shell.contains(event.target)) return;
    closeMenu();
  }

  function onDocumentFocusIn(event) {
    if (shell.contains(event.target)) return;
    closeMenu();
  }

  function onDocumentKeyDown(event) {
    if (event.key === 'Escape') {
      closeMenu({ focusTrigger: true });
    }
  }

  trigger.onclick = () => {
    if (open) {
      closeMenu();
    } else {
      openMenu();
    }
  };

  trigger.onkeydown = (event) => {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp' || event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      openMenu();
    }
  };

  select.addEventListener('change', refresh);
  select.addEventListener('custom-select:refresh', refresh);

  const observer = new MutationObserver(() => refresh());
  observer.observe(select, {
    childList: true,
    subtree: true,
    characterData: true,
    attributes: true,
    attributeFilter: ['disabled']
  });

  refresh();

  return {
    refresh,
    destroy() {
      closeMenu();
      observer.disconnect();
      select.removeEventListener('change', refresh);
      select.removeEventListener('custom-select:refresh', refresh);
      trigger.remove();
      menu.remove();
      select.classList.remove('custom-select-native');
      select.removeAttribute('aria-hidden');
      select.tabIndex = 0;
      delete select.dataset.customSelectBound;
    }
  };
}
