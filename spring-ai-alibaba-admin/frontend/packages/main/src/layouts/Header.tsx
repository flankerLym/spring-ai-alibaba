import { history } from 'umi';
import styles from './index.module.less';

type TChildren = React.ReactNode | React.ReactNode[];

export default function (props: {
  logo?: string;
  children?: TChildren;
  right?: TChildren;
}) {
  return (
    <div className={styles['header']}>
      <button
        type="button"
        className={styles['header-logo']}
        onClick={() => history.push('/')}
        aria-label="尖山 灵析"
      >
        尖山 灵析
      </button>
      {props.children}
      <div className={styles['header-right']}>{props.right}</div>
    </div>
  );
}
