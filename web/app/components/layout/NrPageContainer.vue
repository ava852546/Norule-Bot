<template><div class="nr-container"><slot /></div></template>
<style scoped>.nr-container{width:min(calc(100% - clamp(2rem,6vw,6rem)),var(--nr-container));margin-inline:auto}@media(max-width:640px){.nr-container{width:min(calc(100% - 1.5rem),var(--nr-container))}}</style>
