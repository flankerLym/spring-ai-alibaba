import CardList from '@/components/Card/List';
import { IAppType } from '@/services/appComponent';
import { getAppList } from '@/services/appManage';
import {
  addAppsToProjectArchiveFolder,
  createProjectArchiveFolder,
  deleteProjectArchiveFolder,
  getProjectArchiveFolderApps,
  getProjectArchiveFolders,
  IProjectArchiveFolder,
  removeAppFromProjectArchiveFolder,
  renameProjectArchiveFolder,
} from '@/services/projectArchive';
import { IAppCard } from '@/types/appManage';
import {
  AlertDialog,
  Button,
  Dropdown,
  IconButton,
  IconFont,
  message,
} from '@spark-ai/design';
import { Checkbox, Input, Modal, Spin } from 'antd';
import dayjs from 'dayjs';
import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import AppCard from '../Card';
import ImportDslButton from '../ImportDslButton';
import styles from './index.module.less';

const PAGE_SIZE = 50;
const APP_PICKER_PAGE_SIZE = 100;

export default function ProjectArchive() {
  const navigate = useNavigate();
  const [folders, setFolders] = useState<IProjectArchiveFolder[]>([]);
  const [folderLoading, setFolderLoading] = useState(true);
  const [activeFolder, setActiveFolder] =
    useState<IProjectArchiveFolder | null>(null);

  const [apps, setApps] = useState<IAppCard[]>([]);
  const [appsLoading, setAppsLoading] = useState(false);
  const [current, setCurrent] = useState(1);
  const [size, setSize] = useState(PAGE_SIZE);
  const [total, setTotal] = useState(0);
  const [appKeyword, setAppKeyword] = useState('');
  const [searchedKeyword, setSearchedKeyword] = useState('');

  const [folderModalOpen, setFolderModalOpen] = useState(false);
  const [folderModalLoading, setFolderModalLoading] = useState(false);
  const [editingFolder, setEditingFolder] =
    useState<IProjectArchiveFolder | null>(null);
  const [folderName, setFolderName] = useState('');

  const [appPickerOpen, setAppPickerOpen] = useState(false);
  const [appPickerLoading, setAppPickerLoading] = useState(false);
  const [allApps, setAllApps] = useState<IAppCard[]>([]);
  const [selectedAppIds, setSelectedAppIds] = useState<string[]>([]);
  const [pickerKeyword, setPickerKeyword] = useState('');

  const loadFolders = async () => {
    setFolderLoading(true);
    try {
      const list = await getProjectArchiveFolders();
      setFolders(list || []);
      if (activeFolder) {
        const fresh = list.find(
          (item) => item.folder_id === activeFolder.folder_id,
        );
        if (fresh) setActiveFolder(fresh);
      }
    } finally {
      setFolderLoading(false);
    }
  };

  const loadFolderApps = async (
    folderId: string,
    options: {
      current?: number;
      size?: number;
      name?: string;
    } = {},
  ) => {
    const nextCurrent = options.current ?? current;
    const nextSize = options.size ?? size;
    const nextName = options.name ?? searchedKeyword;
    setAppsLoading(true);
    try {
      const result = await getProjectArchiveFolderApps(folderId, {
        current: nextCurrent,
        size: nextSize,
        name: nextName || undefined,
      });
      setApps(result.records || []);
      setTotal(result.total || 0);
      setCurrent(nextCurrent);
      setSize(nextSize);
    } finally {
      setAppsLoading(false);
    }
  };

  useEffect(() => {
    void loadFolders();
  }, []);

  const openFolder = (folder: IProjectArchiveFolder) => {
    setActiveFolder(folder);
    setCurrent(1);
    setAppKeyword('');
    setSearchedKeyword('');
    void loadFolderApps(folder.folder_id, { current: 1, name: '' });
  };

  const backToFolders = () => {
    setActiveFolder(null);
    setApps([]);
    setTotal(0);
    setCurrent(1);
    setAppKeyword('');
    setSearchedKeyword('');
    void loadFolders();
  };

  const showCreateFolder = () => {
    setEditingFolder(null);
    setFolderName('');
    setFolderModalOpen(true);
  };

  const showRenameFolder = (folder: IProjectArchiveFolder) => {
    setEditingFolder(folder);
    setFolderName(folder.name);
    setFolderModalOpen(true);
  };

  const submitFolder = async () => {
    const name = folderName.trim();
    if (!name) {
      message.warning('请输入文件夹名称');
      return;
    }
    setFolderModalLoading(true);
    try {
      if (editingFolder) {
        const renamed = await renameProjectArchiveFolder(
          editingFolder.folder_id,
          name,
        );
        if (activeFolder?.folder_id === editingFolder.folder_id) {
          setActiveFolder(renamed);
        }
        message.success('文件夹已重命名');
      } else {
        await createProjectArchiveFolder(name);
        message.success('文件夹创建成功');
      }
      setFolderModalOpen(false);
      await loadFolders();
    } finally {
      setFolderModalLoading(false);
    }
  };

  const confirmDeleteFolder = (folder: IProjectArchiveFolder) => {
    AlertDialog.warning({
      title: '删除项目文件夹',
      content: `确定删除“${folder.name}”吗？文件夹中的应用不会被删除，只会取消归档。`,
      onOk: async () => {
        await deleteProjectArchiveFolder(folder.folder_id);
        message.success('文件夹已删除，应用已取消归档');
        if (activeFolder?.folder_id === folder.folder_id) {
          setActiveFolder(null);
        }
        await loadFolders();
      },
    });
  };

  const gotoAppDetail = (item: IAppCard) => {
    navigate(
      `/app/${
        item.type === IAppType.AGENT ? 'assistant' : 'workflow'
      }/${item.app_id}`,
    );
  };

  const removeApp = (item: IAppCard) => {
    if (!activeFolder) return;
    AlertDialog.warning({
      title: '移出文件夹',
      content: `确定将“${item.name}”移出当前文件夹吗？应用本身不会被删除。`,
      onOk: async () => {
        await removeAppFromProjectArchiveFolder(
          activeFolder.folder_id,
          item.app_id,
        );
        message.success('已移出文件夹');
        const nextCurrent = apps.length === 1 && current > 1 ? current - 1 : current;
        await Promise.all([
          loadFolderApps(activeFolder.folder_id, { current: nextCurrent }),
          loadFolders(),
        ]);
      },
    });
  };

  const fetchAllApps = async () => {
    const records: IAppCard[] = [];
    let page = 1;
    let expectedTotal = 0;
    do {
      const result = await getAppList({
        current: page,
        size: APP_PICKER_PAGE_SIZE,
      });
      records.push(...(result.records || []));
      expectedTotal = result.total || 0;
      page += 1;
      // Safety guard for accidental unbounded data sets in the selector.
      if (page > 50) break;
    } while (records.length < expectedTotal);
    return records;
  };

  const showAppPicker = async () => {
    if (!activeFolder) return;
    setAppPickerOpen(true);
    setAppPickerLoading(true);
    setSelectedAppIds([]);
    setPickerKeyword('');
    try {
      setAllApps(await fetchAllApps());
    } finally {
      setAppPickerLoading(false);
    }
  };

  const submitApps = async () => {
    if (!activeFolder || !selectedAppIds.length) return;
    setAppPickerLoading(true);
    try {
      await addAppsToProjectArchiveFolder(
        activeFolder.folder_id,
        selectedAppIds,
      );
      message.success('应用已添加到当前文件夹');
      setAppPickerOpen(false);
      await Promise.all([
        loadFolderApps(activeFolder.folder_id, { current: 1 }),
        loadFolders(),
      ]);
    } finally {
      setAppPickerLoading(false);
    }
  };

  const currentAppIds = useMemo(
    () => new Set(apps.map((item) => item.app_id)),
    [apps],
  );

  const pickerApps = useMemo(() => {
    const keyword = pickerKeyword.trim().toLowerCase();
    return allApps.filter((item) => {
      if (currentAppIds.has(item.app_id)) return false;
      return (
        !keyword ||
        item.name.toLowerCase().includes(keyword) ||
        item.app_id.toLowerCase().includes(keyword)
      );
    });
  }, [allApps, currentAppIds, pickerKeyword]);

  if (!activeFolder) {
    return (
      <div className={styles.archivePage}>
        <div className={styles.toolbar}>
          <div>
            <div className={styles.pageTitle}>项目归档</div>
            <div className={styles.pageDesc}>
              用文件夹整理已经创建的智能体和工作流应用。
            </div>
          </div>
          <Button
            type="primary"
            icon={<IconFont type="spark-plus-line" />}
            onClick={showCreateFolder}
          >
            新建文件夹
          </Button>
        </div>

        <Spin spinning={folderLoading}>
          {folders.length ? (
            <div className={styles.folderGrid}>
              {folders.map((folder) => (
                <div
                  key={folder.folder_id}
                  className={styles.folderCard}
                  onClick={() => openFolder(folder)}
                >
                  <div className={styles.folderVisual}>
                    <div className={styles.folderIcon} />
                  </div>
                  <div className={styles.folderBody}>
                    <div className={styles.folderTitleRow}>
                      <div className={styles.folderName} title={folder.name}>
                        {folder.name}
                      </div>
                      <div
                        onClick={(event) => {
                          event.stopPropagation();
                        }}
                      >
                        <Dropdown
                          menu={{
                            onClick: (event) => {
                              if (event.key === 'rename')
                                showRenameFolder(folder);
                              if (event.key === 'delete')
                                confirmDeleteFolder(folder);
                            },
                            items: [
                              { label: '重命名', key: 'rename' },
                              { label: '删除文件夹', key: 'delete', danger: true },
                            ],
                          }}
                        >
                          <IconButton
                            shape="default"
                            icon="spark-more-line"
                          />
                        </Dropdown>
                      </div>
                    </div>
                    <div className={styles.folderMeta}>
                      {folder.app_count || 0} 个应用
                    </div>
                    <div className={styles.folderTime}>
                      更新于 {dayjs(folder.gmt_modified).format('YYYY-MM-DD HH:mm')}
                    </div>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <div className={styles.emptyFolders}>
              <div className={styles.emptyFolderIcon}>
                <div className={styles.folderIcon} />
              </div>
              <div className={styles.emptyTitle}>还没有项目文件夹</div>
              <div className={styles.emptyDesc}>
                创建文件夹后，可以把已有应用加入文件夹，也可以直接在文件夹中导入 DSL。
              </div>
              <Button
                type="primary"
                icon={<IconFont type="spark-plus-line" />}
                onClick={showCreateFolder}
              >
                新建文件夹
              </Button>
            </div>
          )}
        </Spin>

        <Modal
          title={editingFolder ? '重命名文件夹' : '新建文件夹'}
          open={folderModalOpen}
          onCancel={() => setFolderModalOpen(false)}
          onOk={() => void submitFolder()}
          confirmLoading={folderModalLoading}
          okText="确定"
          cancelText="取消"
        >
          <Input
            autoFocus
            maxLength={100}
            placeholder="请输入文件夹名称"
            value={folderName}
            onChange={(event) => setFolderName(event.target.value)}
            onPressEnter={() => void submitFolder()}
          />
        </Modal>
      </div>
    );
  }

  return (
    <div className={styles.archivePage}>
      <div className={styles.folderHeader}>
        <div className={styles.folderHeaderLeft}>
          <Button onClick={backToFolders}>← 返回文件夹</Button>
          <div>
            <div className={styles.pageTitle}>{activeFolder.name}</div>
            <div className={styles.pageDesc}>
              文件夹内导入 DSL 后会自动归档到这里。
            </div>
          </div>
        </div>
        <div className={styles.folderActions}>
          <Button
            icon={<IconFont type="spark-plus-line" />}
            onClick={() => void showAppPicker()}
          >
            添加已有应用
          </Button>
          <ImportDslButton
            archiveFolderId={activeFolder.folder_id}
            onImported={async () => {
              await Promise.all([
                loadFolderApps(activeFolder.folder_id, { current: 1 }),
                loadFolders(),
              ]);
            }}
          />
        </div>
      </div>

      <div className={styles.appSearchRow}>
        <Input.Search
          allowClear
          placeholder="搜索当前文件夹中的应用"
          value={appKeyword}
          onChange={(event) => setAppKeyword(event.target.value)}
          onSearch={(value) => {
            const keyword = value.trim();
            setSearchedKeyword(keyword);
            void loadFolderApps(activeFolder.folder_id, {
              current: 1,
              name: keyword,
            });
          }}
          className={styles.appSearch}
        />
      </div>

      <CardList
        loading={appsLoading}
        isSearch={!!searchedKeyword}
        pagination={{
          current,
          total,
          pageSize: size,
          onChange: (nextCurrent, nextSize) => {
            void loadFolderApps(activeFolder.folder_id, {
              current: nextCurrent,
              size: nextSize,
            });
          },
        }}
        emptyProps={{
          title: searchedKeyword ? '暂无搜索结果' : '当前文件夹暂无应用',
          description: searchedKeyword
            ? '换个关键词试试'
            : '可以添加已有应用，或直接导入 DSL',
        }}
        emptyAction={
          <div className={styles.emptyActions}>
            <Button onClick={() => void showAppPicker()}>添加已有应用</Button>
            <ImportDslButton
              archiveFolderId={activeFolder.folder_id}
              onImported={async () => {
                await Promise.all([
                  loadFolderApps(activeFolder.folder_id, { current: 1 }),
                  loadFolders(),
                ]);
              }}
            />
          </div>
        }
      >
        {apps.map((item) => (
          <AppCard
            key={item.app_id}
            {...item}
            menuItems={[
              {
                label: '移出当前文件夹',
                key: 'removeArchive',
                danger: true,
              },
            ]}
            onClickAction={(key) => {
              if (key === 'click' || key === 'edit') gotoAppDetail(item);
              if (key === 'removeArchive') removeApp(item);
            }}
          />
        ))}
      </CardList>

      <Modal
        title="添加已有应用"
        open={appPickerOpen}
        width={680}
        onCancel={() => setAppPickerOpen(false)}
        onOk={() => void submitApps()}
        confirmLoading={appPickerLoading}
        okText="添加到当前文件夹"
        cancelText="取消"
        okButtonProps={{ disabled: selectedAppIds.length === 0 }}
      >
        <div className={styles.pickerHint}>
          已在其他项目文件夹中的应用如果被选中，会移动到当前文件夹。
        </div>
        <Input.Search
          allowClear
          placeholder="按应用名称或 ID 搜索"
          value={pickerKeyword}
          onChange={(event) => setPickerKeyword(event.target.value)}
          className={styles.pickerSearch}
        />
        <Spin spinning={appPickerLoading}>
          <Checkbox.Group
            value={selectedAppIds}
            onChange={(values) => setSelectedAppIds(values as string[])}
            className={styles.pickerList}
          >
            {pickerApps.length ? (
              pickerApps.map((item) => (
                <label className={styles.pickerItem} key={item.app_id}>
                  <Checkbox value={item.app_id} />
                  <div className={styles.pickerItemText}>
                    <div className={styles.pickerItemName}>{item.name}</div>
                    <div className={styles.pickerItemMeta}>
                      {item.type === IAppType.AGENT ? '智能体应用' : '工作流应用'}
                      <span> · </span>
                      {item.app_id}
                    </div>
                  </div>
                </label>
              ))
            ) : (
              <div className={styles.pickerEmpty}>没有可添加的应用</div>
            )}
          </Checkbox.Group>
        </Spin>
      </Modal>
    </div>
  );
}
