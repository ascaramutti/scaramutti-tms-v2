-- Los trabajadores anteriores a la auditoria los cargo el usuario admin y quedan
-- firmados por el. V010 los dejo sin autor (su decision 4(a)); el dueno confirmo
-- despues que los cargo el admin. Sin usuario admin no hace nada. updated_by no
-- se toca, para conservar la firma de quien haya editado la fila despues.
UPDATE public.workers
   SET created_by = (SELECT id FROM public.users WHERE username = 'admin')
 WHERE created_by IS NULL
   AND EXISTS (SELECT 1 FROM public.users WHERE username = 'admin');
