import { createBrowserRouter } from 'react-router-dom';

import App from '@/App';

import { BASE_URL, PATH } from './path';

export const router = createBrowserRouter(
  [
    {
      path: PATH.LANDING,
      element: <App />,
      children: [
        {
          index: true,
        },
        {
          path: PATH.HOME,
          element: <div>Home Page</div>,
        },
        {
          path: PATH.EXCEPTION,
          element: <div>Not Found Page</div>,
        },
        {
          path: PATH.LOADING,
          element: <div>Loading Page</div>,
        },
      ],

      errorElement: <div>Error Page</div>,
    },
  ],
  {
    basename: process.env.NODE_ENV === 'production' ? BASE_URL : '',
  },
);
