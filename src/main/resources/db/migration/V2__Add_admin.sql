INSERT INTO public.t_user(id, username, password)
VALUES (1, 'admin', '123456');

INSERT INTO public.t_role(id, name)
VALUES (1, 'USER'), (2, 'ADMIN');

INSERT INTO public.t_user_roles(user_id, roles_id)
VALUES (1, 2);