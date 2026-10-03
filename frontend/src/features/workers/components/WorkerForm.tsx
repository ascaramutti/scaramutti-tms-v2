import { useEffect, useMemo, useRef, type MouseEvent } from 'react'
import { FormProvider, useForm, useWatch, type FieldPath } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import type { DocumentTypeResponse, RoleResponse, WorkerDetailResponse } from '../../../api'
import { Alert } from '../../../shared/ui/Alert'
import { Button } from '../../../shared/ui/Button'
import { buildWorkerFormSchema, driverProfileOf, type WorkerFormValues } from '../schemas/worker.schema'
import { applyWorkerApiError, firstInFormOrder, type WorkerApiError } from '../workerApiErrors'
import { WorkerDriverSection } from './WorkerDriverSection'
import { WorkerFormFields } from './WorkerFormFields'

interface WorkerFormProps {
  documentTypes: readonly DocumentTypeResponse[]
  /** El catálogo completo: la modalidad de licencia sale de acá. */
  roles: readonly RoleResponse[]
  /** Los cargos que la sesión puede asignar. */
  roleOptions: readonly RoleResponse[]
  defaultValues: WorkerFormValues
  save: (values: WorkerFormValues) => Promise<WorkerDetailResponse>
  errors: Record<string, WorkerApiError>
  fallbackMessage: string
  onSaved: (saved: WorkerDetailResponse) => void
  onCancel: () => void
  reloadCatalog: (catalog: 'documentTypes' | 'roles') => void
}

/** El formulario del trabajador: los campos, la licencia según el cargo y los errores del backend. */
export function WorkerForm({
  documentTypes,
  roles,
  roleOptions,
  defaultValues,
  save,
  errors,
  fallbackMessage,
  onSaved,
  onCancel,
  reloadCatalog,
}: WorkerFormProps) {
  const schema = useMemo(() => buildWorkerFormSchema({ documentTypes, roles }), [documentTypes, roles])
  const form = useForm<WorkerFormValues>({
    resolver: zodResolver(schema),
    defaultValues,
    // Como los formularios de almacén: avisa al salir del campo y después corrige en vivo.
    mode: 'onTouched',
  })
  const { handleSubmit, setError, setValue, getValues, getFieldState, setFocus, trigger, control, formState } = form
  const role = useWatch({ control, name: 'role' })
  const profile = driverProfileOf(role, roles)

  // Un catálogo recargado (tras WRK-003, WRK-005 o WRK-008) puede dejar afuera el valor
  // elegido: lo que se envía tiene que ser lo que se ve. El error del campo se queda.
  useEffect(() => {
    if (!documentTypes.some((type) => type.id === getValues('documentTypeId'))) {
      setValue('documentTypeId', documentTypes[0]?.id ?? null)
    }
  }, [documentTypes, getValues, setValue])
  useEffect(() => {
    const current = getValues('role')
    if (current !== null && !roleOptions.some((option) => option.name === current)) {
      setValue('role', null)
    }
  }, [roleOptions, getValues, setValue])

  // Con una licencia ya escrita, pasar a un cargo donde es opcional no la pierde:
  // la casilla nace marcada, porque la intención ya estaba dicha.
  useEffect(() => {
    if (profile === 'OPTIONAL' && getValues('driver.licenseNumber').trim()) {
      setValue('hasLicense', true)
    }
  }, [profile, getValues, setValue])

  // El número se mide contra el tipo: cambiar el tipo lo vuelve a medir si el usuario ya pasó
  // por el número (para llegar al tipo hay que salir de él). Solo ante un cambio real del tipo:
  // revalidar sin motivo borraría el error que acaba de poner el backend.
  const documentTypeId = useWatch({ control, name: 'documentTypeId' })
  const measuredType = useRef(documentTypeId)
  useEffect(() => {
    if (measuredType.current === documentTypeId) return
    measuredType.current = documentTypeId
    if (getFieldState('documentNumber').isTouched) void trigger('documentNumber')
  }, [documentTypeId, getFieldState, trigger])

  // Tras un error del backend, el foco va al primer campo marcado en el orden del formulario,
  // o a Guardar si el error no tiene campo. Recién al terminar el envío: mientras envía, los
  // controles están deshabilitados y el navegador no enfoca uno deshabilitado.
  const pendingFocus = useRef<FieldPath<WorkerFormValues> | 'submit' | null>(null)
  const submitRef = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    if (formState.isSubmitting || !pendingFocus.current) return
    if (pendingFocus.current === 'submit') submitRef.current?.focus()
    else setFocus(pendingFocus.current)
    pendingFocus.current = null
  }, [formState.isSubmitting, setFocus])

  const submitWorker = async (values: WorkerFormValues) => {
    try {
      onSaved(await save(values))
    } catch (error) {
      const marked: FieldPath<WorkerFormValues>[] = []
      applyWorkerApiError(error, {
        errors,
        setError: (field, detail) => {
          marked.push(field as FieldPath<WorkerFormValues>)
          setError(field, detail)
        },
        reload: reloadCatalog,
        fallbackMessage,
      })
      pendingFocus.current = firstInFormOrder(marked) ?? 'submit'
    }
  }

  const sinCargos = roleOptions.length === 0
  // El botón no se lleva el foco al presionarlo: si el campo que se deja mostrara su error en
  // ese momento, el aviso correría los botones y el clic caería afuera. El teclado no cambia.
  const keepFieldFocus = (event: MouseEvent<HTMLButtonElement>) => event.preventDefault()

  return (
    <FormProvider {...form}>
      <form noValidate onSubmit={(event) => handleSubmit(submitWorker)(event)} aria-busy={formState.isSubmitting} className="space-y-6">
        <WorkerFormFields documentTypes={documentTypes} roleOptions={roleOptions} disabled={formState.isSubmitting} />
        <WorkerDriverSection profile={profile} disabled={formState.isSubmitting} />
        {sinCargos && (
          <Alert variant="warning" role="status" className="px-4 py-3 text-sm">
            No hay cargos que puedas asignar con tu rol.
          </Alert>
        )}
        <div className="flex items-center justify-end gap-3">
          <Button
            type="button"
            variant="secondary"
            onMouseDown={keepFieldFocus}
            onClick={onCancel}
            disabled={formState.isSubmitting}
          >
            Cancelar
          </Button>
          <Button
            ref={submitRef}
            type="submit"
            onMouseDown={keepFieldFocus}
            disabled={formState.isSubmitting || sinCargos}
          >
            {formState.isSubmitting ? 'Guardando…' : 'Guardar'}
          </Button>
        </div>
      </form>
    </FormProvider>
  )
}
