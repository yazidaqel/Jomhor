import { useState } from 'react'

/**
 * Open state of a table's dialogs (create or edit form, delete confirmation), owned by the page so a dialog
 * outlives the row it acts on. Closing keeps `item`, so the dialog does not change content while it animates out.
 */
export function useDialog<T>() {
  const [state, setState] = useState<{ open: boolean; mode: 'form' | 'delete'; item?: T }>({ open: false, mode: 'form' })
  const onOpenChange = (open: boolean) => setState((s) => ({ ...s, open }))
  return {
    item: state.item,
    /** `item` undefined: create. */
    edit: (item?: T) => setState({ open: true, mode: 'form', item }),
    remove: (item: T) => setState({ open: true, mode: 'delete', item }),
    form: { open: state.open && state.mode === 'form', onOpenChange },
    confirm: { open: state.open && state.mode === 'delete', onOpenChange },
  }
}
